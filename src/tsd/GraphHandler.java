// This file is part of OpenTSDB.
// Copyright (C) 2010  StumbleUpon, Inc.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.tsd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.core.Aggregator;
import net.opentsdb.core.Aggregators;
import net.opentsdb.core.DataPoints;
import net.opentsdb.core.Query;
import net.opentsdb.core.TSDB;
import net.opentsdb.core.Tags;
import net.opentsdb.graph.Plot;
import net.opentsdb.uid.NoSuchUniqueName;

/**
 * Stateless handler of HTTP graph requests (the {@code /q} endpoint).
 */
final class GraphHandler implements HttpHandler.Command {

  private static final Logger LOG =
    LoggerFactory.getLogger(GraphHandler.class);

  /** The TSDB to use. */
  private final TSDB tsdb;

  /** Directory where to cache query results. */
  private final String cachedir;

  /**
   * Constructor.
   * @param tsdb The TSDB to use.
   */
  public GraphHandler(final TSDB tsdb) {
    this.tsdb = tsdb;
    cachedir = HttpHandler.getDirectoryFromSystemProp("tsd.http.cachedir");
  }

  public void process(final HttpQuery query) throws IOException {
    final String basepath = getGnuplotBasePath(query);
    final long start_time = getQueryStringDate(query, "start");
    if (start_time == -1) {
      throw BadRequestException.missingParameter("start");
    }
    long end_time = getQueryStringDate(query, "end");
    if (end_time == -1) {
      end_time = System.currentTimeMillis() / 1000;
    }
    if (isDiskCacheHit(query, start_time, end_time, basepath)) {
      return;
    }
    Query tsdbquery;
    try {
      tsdbquery = parseQuery(query);
    } catch (BadRequestException e) {
      if (query.hasQueryStringParam("json")) {
        final String err = e.getMessage();
        final StringBuilder buf = new StringBuilder(10 + err.length());
        // TODO(tsuna): JSON-escape.
        buf.append("{\"err\":\"").append(err).append("\"}");
        query.sendReply(buf);
        return;
      } else {
        throw e;
      }
    }
    try {
      tsdbquery.setStartTime(start_time);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("start time: " + e.getMessage());
    }
    try {
      tsdbquery.setEndTime(end_time);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("end time: " + e.getMessage());
    }
    final Plot plot = new Plot(start_time, end_time);
    setPlotDimensions(query, plot);
    setPlotParams(query, plot);
    final HashSet<String> aggregated_tags = new HashSet<String>();
    int npoints = 0;
    try {  // execute the TSDB query!
      // XXX This is slow and will block Netty.  TODO(tsuna): Don't block.
      final DataPoints[] series = tsdbquery.run();
      tsdbquery = null;  // free()
      for (final DataPoints datapoints : series) {
        plot.add(datapoints, "");
        aggregated_tags.addAll(datapoints.getAggregatedTags());
        npoints += datapoints.aggregatedSize();
      }
    } catch (RuntimeException e) {
      logInfo(query, "Query failed (stack trace coming): " + tsdbquery);
      throw e;
    }

    {
      final int nplotted = runGnuplot(basepath, plot);

      if (query.hasQueryStringParam("json")) {
        final StringBuilder buf = new StringBuilder(64);
        buf.append("{\"plotted\":").append(nplotted)
          .append(",\"points\":").append(npoints)
          .append(",\"etags\":");
        if (aggregated_tags.isEmpty()) {
          buf.append("[]");
        } else {
          query.toJsonArray(aggregated_tags, buf);
        }
        // The "timing" field must remain last, loadCachedJson relies this.
        buf.append(",\"timing\":").append(query.processingTimeMillis())
          .append('}');
        query.sendReply(buf);
        final String json_path = basepath + ".json";
        try {
          final FileOutputStream json_cache = new FileOutputStream(json_path);
          try {
            json_cache.write(buf.toString().getBytes());
          } finally {
            json_cache.close();
          }
        } catch (FileNotFoundException e) {
          logError(query, "Failed to create JSON cache file " + json_path, e);
        } catch (IOException e) {
          logError(query, "Failed to write JSON cache file " + json_path, e);
        }
      } else {
          if (query.hasQueryStringParam("png")) {
            query.sendFile(basepath + ".png");
          } else {
            if (nplotted > 0) {
              query.sendReply(query.makePage("TSDB Query", "Your graph is ready",
                "<img src=\"" + query.request().getUri() + "&amp;png\"/><br/>"
                + "<small>(" + nplotted + " points plotted in "
                + query.processingTimeMillis() + "ms)</small>"));
            } else {
              query.sendReply(query.makePage("TSDB Query", "No results found",
                "<blockquote><h1>No results</h1>Your query didn't return"
                + " anything.  Try changing some parameters.</blockquote>"));
            }
          }
      }

      // TODO(tsuna): Expire old files from the on-disk cache.
    }
  }

  /** Returns the base path to use for the Gnuplot files. */
  private String getGnuplotBasePath(final HttpQuery query) {
    // Super cheap caching mechanism: hash the query string.
    final HashMap<String, List<String>> qs =
      new HashMap<String, List<String>>(query.getQueryString());
    // But first remove the parameters that don't influence the output.
    qs.remove("json");
    return cachedir + Integer.toHexString(qs.hashCode());
  }

  /**
   * Checks whether or not it's possible to re-serve this query from disk.
   * @param query The query to serve.
   * @param start_time The UNIX timestamp corresponding to the {@code start}
   * query string parameter.
   * @param end_time The UNIX timestamp corresponding to the {@code end}
   * query string parameter, or corresponding to "now" if it wasn't given.
   * @param basepath The base path used for the Gnuplot files.
   * @return {@code true} if this request was served from disk (in which
   * case processing can stop here), {@code false} otherwise (in which case
   * the query needs to be processed).
   */
  private boolean isDiskCacheHit(final HttpQuery query,
                                 final long start_time,
                                 final long end_time,
                                 final String basepath) throws IOException {
    final String cachepath = basepath + ".png";
    final File cachedfile = new File(cachepath);
    if (cachedfile.exists()) {
      final long bytes = cachedfile.length();
      if (bytes < 21) {  // Minimum possible size for a PNG: 21 bytes.
        logWarn(query, "Cached " + cachepath + " is too small ("
                + bytes + " bytes) to be valid.  Ignoring it.");
        return false;
      }
      logInfo(query, "Found cached file @ " + cachepath + " bytes: " + bytes);
      if (staleCacheFile(query, start_time, end_time, cachedfile)) {
        return false;
      }
      if (query.hasQueryStringParam("json")) {
        StringBuilder json = loadCachedJson(query, start_time, end_time,
                                            basepath);
        if (json == null) {
          json = new StringBuilder(32);
          json.append("{\"timing\":");
        }
        json.append(query.processingTimeMillis())
          .append(",\"cachehit\":\"disk\"}");
        query.sendReply(json);
      } else if (query.hasQueryStringParam("png")) {
        query.sendFile(cachepath);
      } else {
        query.sendReply(query.makePage("TSDB Query", "Your graph is ready",
            "<img src=\"" + query.request().getUri() + "&amp;png\"/><br/>"
            + "<small>(served from disk cache)</small>"));
      }
      return true;
    }
    // We didn't find an image.  Do a negative cache check.  If we've seen
    // this query before but there was no result, we at least wrote the JSON.
    final StringBuilder json = loadCachedJson(query, start_time, end_time,
                                              basepath);
    // If we don't have a JSON file it's a complete cache miss.  If we have
    // one, and it says 0 data points were plotted, it's a negative cache hit.
    if (json == null || !json.toString().contains("\"plotted\":0")) {
      return false;
    }
    if (query.hasQueryStringParam("json")) {
      json.append(query.processingTimeMillis())
        .append(",\"cachehit\":\"disk\"}");
      query.sendReply(json);
    } else if (query.hasQueryStringParam("png")) {
      query.sendReply(" ");  // Send back an empty response...
    } else {
        query.sendReply(query.makePage("TSDB Query", "No results",
            "Sorry, your query didn't return anything.<br/>"
            + "<small>(served from disk cache)</small>"));
    }
    return true;
  }

  /**
   * Returns whether or not the given cache file can be used or is stale.
   * @param query The query to serve.
   * @param start_time The UNIX timestamp corresponding to the {@code start}
   * query string parameter.
   * @param end_time The UNIX timestamp corresponding to the {@code end}
   * query string parameter, or corresponding to "now" if it wasn't given.
   * @param cachedfile The file to check for staleness.
   */
  private boolean staleCacheFile(final HttpQuery query,
                                 final long start_time,
                                 final long end_time,
                                 final File cachedfile) {
    // Queries that don't specify an end-time must be handled carefully,
    // since time passes and we may need to regenerate the results in case
    // new data points have arrived in the mean time.
    if (!query.hasQueryStringParam("end")) {
      final long mtime = cachedfile.lastModified() / 1000;
      final long staleness = end_time - mtime;  // How many seconds stale?
      if (staleness < 0) {  // Can happen if the mtime is "in the future".
        logWarn(query, "Not using file @ " + cachedfile + " with weird"
                + " mtime=" + mtime + " > request end_time=" + end_time);
        return true;
      }
      final long max_age = (end_time - start_time) >> 10;  // 0.1%
      // If our graph is older than 0.1% of the duration of the request,
      // let's regenerate it in order to avoid service data that's too
      // stale, e.g., for 1h of data, it's OK to serve something 3s stale.
      if (staleness > max_age) {
        logInfo(query, "Cached file @ " + cachedfile.getPath() + " is "
                + staleness + "s stale, which is more than its limit of "
                + max_age + "s, and needs to be regenerated.");
        return true;
      }
    }
    return false;
  }

  /**
   * Reads a file into a byte array.
   * @param query The query being handled (for logging purposes).
   * @param file The file to read.
   * @param max_length The maximum number of bytes to read from the file.
   * @return {@code null} if the file doesn't exist or is empty or couldn't be
   * read, otherwise a byte array of up to {@code max_length} bytes.
   */
  private static byte[] readFile(final HttpQuery query,
                                 final File file,
                                 final int max_length) {
    final int length = (int) file.length();
    if (length <= 0) {
      return null;
    }
    FileInputStream in;
    try {
      in = new FileInputStream(file.getPath());
    } catch (FileNotFoundException e) {
      return null;
    }
    try {
      final byte[] buf = new byte[Math.min(length, max_length)];
      final int read = in.read(buf);
      if (read != buf.length) {
        logError(query, "When reading " + file + ": read only "
                 + read + " bytes instead of " + buf.length);
        return null;
      }
      return buf;
    } catch (IOException e) {
      logError(query, "Error while reading " + file, e);
      return null;
    } finally {
      try {
        in.close();
      } catch (IOException e) {
        logError(query, "Error while closing " + file, e);
      }
    }
  }

  /**
   * Attempts to read the cached {@code .json} file for this query.
   * @param query The query to serve.
   * @param start_time The UNIX timestamp corresponding to the {@code start}
   * query string parameter.
   * @param end_time The UNIX timestamp corresponding to the {@code end}
   * query string parameter, or corresponding to "now" if it wasn't given.
   * @param basepath The base path used for the Gnuplot files.
   * @return {@code null} in case no file was found, or the contents of the
   * file if it was found.  In case some contents was found, it is truncated
   * after the position of the last `:' in order to allow the caller to add
   * the time taken to serve by the request and other JSON elements if wanted.
   */
  private StringBuilder loadCachedJson(final HttpQuery query,
                                       final long start_time,
                                       final long end_time,
                                       final String basepath) {
    final String json_path = basepath + ".json";
    File json_cache = new File(json_path);
    if (!json_cache.exists() || staleCacheFile(query, start_time, end_time,
                                               json_cache)) {
      return null;
    }
    final byte[] json = readFile(query, json_cache, 4096);
    if (json == null) {
      return null;
    }
    json_cache = null;
    final StringBuilder buf = new StringBuilder(20 + json.length);
    // The json file is always expected to end in: {...,"timing":N}
    // We remove everything past the last `:' so we can send the new
    // timing for this request.  This doesn't work if there's a tag name
    // with a `:' in it, which is not allowed right now.
    int colon = 0;  // 0 isn't a valid value.
    for (int i = 0; i < json.length; i++) {
      buf.append((char) json[i]);
      if (json[i] == ':') {
        colon = i;
      }
    }
    if (colon != 0) {
      buf.setLength(colon + 1);
      return buf;
    } else {
      logError(query, "No `:' found in " + json_path + " (" + json.length
               + " bytes) = " + new String(json));
    }
    return null;
  }

  /** Parses the {@code wxh} query parameter to set the graph dimension. */
  static void setPlotDimensions(final HttpQuery query, final Plot plot) {
    final String wxh = query.getQueryStringParam("wxh");
    if (wxh != null && !wxh.isEmpty()) {
      final int wxhlength = wxh.length();
      if (wxhlength < 7) {  // 100x100 minimum.
        throw new BadRequestException("Parameter wxh too short: " + wxh);
      }
      final int x = wxh.indexOf('x', 3);  // Start at 2 as min size is 100x100
      if (x < 0) {
        throw new BadRequestException("Invalid wxh parameter: " + wxh);
      }
      try {
        final short width = Short.parseShort(wxh.substring(0, x));
        final short height = Short.parseShort(wxh.substring(x + 1, wxhlength));
        try {
          plot.setDimensions(width, height);
        } catch (IllegalArgumentException e) {
          throw new BadRequestException("Invalid wxh parameter: " + wxh + ", "
                                        + e.getMessage());
        }
      } catch (NumberFormatException e) {
        throw new BadRequestException("Can't parse wxh '" + wxh + "': "
                                      + e.getMessage());
      }
    }
  }

  static void setPlotParams(final HttpQuery query, final Plot plot) {
    final HashMap<String, String> params = new HashMap<String, String>();
    // XXX Global and per-graph Gnuplot parameters.  Just doing yrange now.
    final String yrange = query.getQueryStringParam("yrange");
    if (yrange != null) {
      params.put("yrange", yrange);
    }
    plot.setParams(params);
  }

  /**
   * Runs Gnuplot in a subprocess to generate the graph.
   * @param basepath The base path used for the Gnuplot files.
   * @param plot The plot object to generate Gnuplot's input files.
   * @return The number of points plotted by Gnuplot (0 or more).
   * @throws IOException if the Gnuplot files can't be written, or
   * the Gnuplot subprocess fails to start, or we can't read the
   * graph from the file it produces, or if we have been interrupted.
   */
  private static int runGnuplot(final String basepath,
                                final Plot plot) throws IOException {
    final int nplotted = plot.dumpToFiles(basepath);
    final Process gnuplot = new ProcessBuilder(
        // XXX Java Kludge XXX
        "./graph/mygnuplot.sh", basepath + ".out", basepath + ".err",
                                basepath + ".gnuplot").start();
    int rv;
    try {
      rv = gnuplot.waitFor();  // TODO(tsuna): How to do this asynchronously.
    } catch (InterruptedException e) {
      gnuplot.destroy();
      Thread.currentThread().interrupt();  // Restore the interrupted status.
      throw new IOException("interrupted", e);  // I hate checked exceptions.
    }
    if (rv != 0) {
      throw new RuntimeException("Gnuplot returned " + rv);
    }
    return nplotted;
  }

  /**
   * Parses the {@code /q} query in a {@link Query} object.
   * @param query The HTTP query for {@code /q}.
   * @return The corresponding {@link Query} object.
   * @throws BadRequestException if the query was malformed.
   */
  private final Query parseQuery(final HttpQuery query) {
    final Query tsdbquery = tsdb.newQuery();
    {  // metric, tags, aggregator, rate.
      final List<String> tags = query.getQueryStringParams("tag");
      final HashMap<String, String> parsedtags =
        new HashMap<String, String>(tags == null ? 0 : tags.size());
      if (tags != null) {
        for (final String tag : tags) {
          try {
            Tags.parse(parsedtags, tag);
          } catch (IllegalArgumentException e) {
            throw new BadRequestException("When parsing tag '" + tag
                                          + "': " + e.getMessage());
          }
        }
      }
      final Aggregator agg =
        getAggregator(query.getRequiredQueryStringParam("af"));
      final String metric = query.getRequiredQueryStringParam("m");
      final boolean rate = query.hasQueryStringParam("rate");
      try {
        tsdbquery.setTimeSeries(metric, parsedtags, agg, rate);
      } catch (NoSuchUniqueName e) {
        throw new BadRequestException(e.getMessage());
      }
    }
    {  // downsampling function & interval.
      final String df = query.getQueryStringParam("df");
      if (df != null) {
        Aggregator downsampler;
        try {
          downsampler = Aggregators.get(df);
        } catch (NoSuchElementException e) {
          throw new BadRequestException("No such downsampling function: " + df);
        }
        final int interval = parseDuration(query.getQueryStringParam("di"));
        tsdbquery.downsample(interval, downsampler);
      }
    }
    return tsdbquery;
  }

  /**
   * Returns the aggregator with the given name.
   * @param name Name of the aggregator to get.
   * @throws BadRequestException if there's no aggregator with this name.
   */
  private static final Aggregator getAggregator(final String name) {
    try {
      return Aggregators.get(name);
    } catch (NoSuchElementException e) {
      throw new BadRequestException("No such aggregation function: " + name);
    }
  }

  /**
   * Parses a human-readable duration (e.g, "10m", "3h", "14d") into seconds.
   * <p>
   * Formats supported: {@code s}: seconds, {@code m}: minutes,
   * {@code h}: hours, {@code d}: days, {@code w}: weeks, {@code y}: years.
   * @param duration The human-readable duration to parse.
   * @return A strictly positive number of seconds.
   * @throws BadRequestException if the interval was malformed.
   */
  private static final int parseDuration(final String duration) {
    int interval;
    final int lastchar = duration.length() - 1;
    try {
      interval = Integer.parseInt(duration.substring(0, lastchar));
    } catch (NumberFormatException e) {
      throw new BadRequestException("Invalid duration (number): " + duration);
    }
    if (interval <= 0) {
      throw new BadRequestException("Zero or negative duration: " + duration);
    }
    switch (duration.charAt(lastchar)) {
      case 's': return interval;                    // seconds
      case 'm': return interval * 60;               // minutes
      case 'h': return interval * 3600;             // hours
      case 'd': return interval * 3600 * 24;        // days
      case 'w': return interval * 3600 * 24 * 7;    // weeks
      case 'y': return interval * 3600 * 24 * 365;  // years (screw leap years)
    }
    throw new BadRequestException("Invalid duration (suffix): " + duration);
  }

  /**
   * Returns a timestamp from a date specified in a query string parameter.
   * @param query The HTTP query from which to get the query string parameter.
   * @param paramname The name of the query string parameter.
   * @return A UNIX timestamp in seconds (strictly positive 32-bit "unsigned")
   * or -1 if there was no query string parameter named {@code paramname}.
   * @throws BadRequestException if the date is invalid.
   */
  private long getQueryStringDate(final HttpQuery query,
                                  final String paramname) {
    final String date = query.getQueryStringParam(paramname);
    if (date == null) {
      return -1;
    }
    try {
      final SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd-HH:mm:ss");
      final long timestamp = fmt.parse(date).getTime() / 1000;
      if (timestamp <= 0) {
        throw new BadRequestException("Bad " + paramname + " date: " + date);
      }
      return timestamp;
    } catch (ParseException e) {
      throw new BadRequestException("Invalid " + paramname + " date: " + date
                                    + ". " + e.getMessage());
    } catch (NumberFormatException e) {
      throw new BadRequestException("Invalid " + paramname + " date: " + date
                                    + ". " + e.getMessage());
    }
  }

  // ---------------- //
  // Logging helpers. //
  // ---------------- //

  static void logInfo(final HttpQuery query, final String msg) {
    LOG.info(query.channel().toString() + ' ' + msg);
  }

  static void logWarn(final HttpQuery query, final String msg) {
    LOG.warn(query.channel().toString() + ' ' + msg);
  }

  static void logError(final HttpQuery query, final String msg) {
    LOG.error(query.channel().toString() + ' ' + msg);
  }

  static void logError(final HttpQuery query, final String msg,
                       final Throwable e) {
    LOG.error(query.channel().toString() + ' ' + msg, e);
  }

}
