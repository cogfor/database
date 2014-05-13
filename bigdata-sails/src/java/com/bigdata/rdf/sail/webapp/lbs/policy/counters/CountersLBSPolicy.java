/**
Copyright (C) SYSTAP, LLC 2006-2014.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package com.bigdata.rdf.sail.webapp.lbs.policy.counters;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

import com.bigdata.bop.engine.QueryEngine;
import com.bigdata.bop.fed.QueryEngineFactory;
import com.bigdata.counters.CounterSet;
import com.bigdata.counters.DefaultInstrumentFactory;
import com.bigdata.journal.IIndexManager;
import com.bigdata.journal.Journal;
import com.bigdata.journal.PlatformStatsPlugIn;
import com.bigdata.journal.jini.ha.HAJournalServer;
import com.bigdata.rdf.sail.webapp.CountersServlet;
import com.bigdata.rdf.sail.webapp.client.ConnectOptions;
import com.bigdata.rdf.sail.webapp.client.IMimeTypes;
import com.bigdata.rdf.sail.webapp.client.RemoteRepository;
import com.bigdata.rdf.sail.webapp.lbs.AbstractHostLBSPolicy;
import com.bigdata.rdf.sail.webapp.lbs.IHALoadBalancerPolicy;
import com.bigdata.rdf.sail.webapp.lbs.IHostMetrics;
import com.bigdata.rdf.sail.webapp.lbs.IHostScoringRule;
import com.bigdata.rdf.sail.webapp.lbs.ServiceScore;

/**
 * Stochastically proxy the request to the services based on their load.
 * <p>
 * Note: This {@link IHALoadBalancerPolicy} has a dependency on the
 * {@link PlatformStatsPlugIn}. The plugin must be setup to publish out
 * performance counters using the {@link CounterServlet}. This policy will
 * periodically query the different {@link HAJournalServer} instances to
 * obtain their current metrics using that {@link CountersServlet}.
 * <p>
 * This is not as efficient as using ganglia. However, this plugin creates
 * fewer dependencies and is significantly easier to administer if the
 * network does not support UDP multicast.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */
public class CountersLBSPolicy extends AbstractHostLBSPolicy {

    private static final Logger log = Logger.getLogger(CountersLBSPolicy.class);

    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    /**
     * Servlet <code>init-param</code> values understood by the
     * {@link CountersLBSPolicy}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan
     *         Thompson</a>
     */
    public interface InitParams extends AbstractHostLBSPolicy.InitParams {

    }

    /**
     * The most recent host metrics for each host running a service of interest.
     * 
     * TODO Does not track per-service metrics unless we change to the service
     * {@link UUID} as the key. This means that we can not monitor the GC load
     * associated with a specific JVM instance.
     */
    private final ConcurrentHashMap<String/* hostname */, IHostMetrics> hostMetricsMap = new ConcurrentHashMap<String, IHostMetrics>();

    @Override
    protected void toString(final StringBuilder sb) {

        super.toString(sb);

    }

    @Override
    public void init(final ServletConfig servletConfig,
            final IIndexManager indexManager) throws ServletException {

        super.init(servletConfig, indexManager);

    }

    @Override
    public void destroy() {

        super.destroy();

    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation issues HTTP requests to obtain the up to date
     * performance counters for each host on which a service is known to be
     * running.
     */
    @Override
    protected Map<String, IHostMetrics> getHostReportForKnownServices(
            final IHostScoringRule scoringRule,
            final ServiceScore[] serviceScores) {
        
        /*
         * The set of hosts having services that are joined with the met quorum.
         */
        final String[] hosts;
        {
            final List<String> tmp = new LinkedList<String>();
            for (ServiceScore serviceScore : serviceScores) {
                if (serviceScore == null) // should never be null.
                    continue;
                final String hostname = serviceScore.getHostname();
                if (hostname == null) // should never be null.
                    continue;
                tmp.add(hostname);
            }
            // dense array of hosts names for services.
            hosts = tmp.toArray(new String[tmp.size()]);
        }

        final ClientConnectionManager cm = getClientConnectionManager();

        for (String hostname : hosts) {

            final String baseRequestURI = getServiceScoreForHostname(hostname)
                    .getRequestURI();
            
            // HTTP GET => Counters XML
            final CounterSet counterSet;
            try {
                counterSet = doCountersQuery(cm, hostname, baseRequestURI,
                        nextValue.incrementAndGet());
            } catch (Exception ex) {
                log.error(ex, ex);
                continue;
            }

            if (counterSet.isRoot() && counterSet.isLeaf()) {
                log.warn("No data: hostname=" + hostname);
                continue;
            }
            
            // Add to the map.
            hostMetricsMap.put(hostname, new CounterSetHostMetricsWrapper(
                    counterSet));

        }

        return hostMetricsMap;

    }

    private ClientConnectionManager getClientConnectionManager() {

        final Journal journal = getJournal();

        QueryEngine queryEngine = QueryEngineFactory
                .getExistingQueryController(journal);

        if (queryEngine == null) {

            /*
             * No queries have been run. We do not have access to the HTTPClient
             * yet.
             * 
             * TODO This could cause a race condition with the shutdown of the
             * journal. Perhaps use synchronized(journal) {} here?
             */
            queryEngine = QueryEngineFactory.getQueryController(journal);

        }

        final ClientConnectionManager cm = queryEngine
                .getClientConnectionManager();

        return cm;

    }
    
    /**
     * Do an HTTP GET to the remote service and return the platform performance
     * metrics for that service.
     * 
     * @param cm
     * @param hostname
     * @param baseRequestURI
     * @param uniqueId
     * @return
     * @throws Exception
     */
    private static CounterSet doCountersQuery(final ClientConnectionManager cm,
            final String hostname, final String baseRequestURI,
            final int uniqueId) throws Exception {

        final String uriStr = baseRequestURI + "/counters";

        final ConnectOptions o = new ConnectOptions(uriStr);

        o.setAcceptHeader(ConnectOptions.MIME_APPLICATION_XML);

        o.method = "GET";
                
        // OS counters are under the hostname.
        o.addRequestParam("path", "/" + hostname + "/");

        // Note: Necessary to each counters. E.g., /hostname/CPU/XXXX.
        o.addRequestParam("depth", "3");

        // Used to defeat the httpd cache on /counters.
        o.addRequestParam("uniqueId", Integer.toString(uniqueId));

        final DefaultHttpClient httpClient = new DefaultHttpClient(cm);

        // Setup a standard strategy for following redirects.
        httpClient.setRedirectStrategy(new DefaultRedirectStrategy());

        HttpResponse response = null;
        HttpEntity entity = null;
        boolean didDrainEntity = false;
        try {

            response = doConnect(httpClient, o);

            RemoteRepository.checkResponseCode(response);

            entity = response.getEntity();

            // Check the mime type for something we can handle.
            final String contentType = entity.getContentType().getValue();

            if (!contentType.startsWith(IMimeTypes.MIME_APPLICATION_XML)) {

                throw new IOException("Expecting "
                        + IMimeTypes.MIME_APPLICATION_XML
                        + ", not Content-Type=" + contentType);
 
            }

            final CounterSet counterSet = new CounterSet();

            final InputStream is = entity.getContent();

            try {

                /*
                 * Note: This will throw a runtime exception if the source
                 * contains more than 60 minutes worth of history data.
                 */
                counterSet
                        .readXML(is, DefaultInstrumentFactory.NO_OVERWRITE_60M,
                                null/* filter */);

                didDrainEntity = true;

                if (log.isDebugEnabled())
                    log.debug("hostname=" + hostname + ": counters="
                            + counterSet);

                return counterSet;
                
            } finally {

                try {
                    is.close();
                } catch (IOException ex) {
                    log.warn(ex);
                }

            }

        } finally {
            
            if (entity != null && !didDrainEntity) {
                try {
                    EntityUtils.consume(entity);
                } catch (IOException ex) {
                    log.warn(ex);
                }
            }
            
        }
        
    }
    
    /**
     * Connect to an HTTP end point.
     * 
     * @param opts
     *            The connection options.
     * 
     * @return The connection.
     */
    static private HttpResponse doConnect(final DefaultHttpClient httpClient,
            final ConnectOptions opts) throws IOException {

        /*
         * Generate the fully formed and encoded URL.
         */
        // The requestURL (w/o URL query parameters).
        final String requestURL = opts.serviceURL;
        
        final StringBuilder urlString = new StringBuilder(requestURL);

        ConnectOptions.addQueryParams(urlString, opts.requestParams);

        if (log.isDebugEnabled()) {
            log.debug("*** Request ***");
            log.debug(requestURL);
            log.debug(opts.method);
            log.debug(urlString.toString());
        }

        HttpUriRequest request = null;
        try {

            request = RemoteRepository.newRequest(urlString.toString(),
                    opts.method);

            if (opts.requestHeaders != null) {

                for (Map.Entry<String, String> e : opts.requestHeaders
                        .entrySet()) {

                    request.addHeader(e.getKey(), e.getValue());

                    if (log.isDebugEnabled())
                        log.debug(e.getKey() + ": " + e.getValue());

                }

            }
            
            if (opts.entity != null) {

                ((HttpEntityEnclosingRequestBase) request)
                        .setEntity(opts.entity);

            }

            final HttpResponse response = httpClient.execute(request);
            
            return response;

        } catch (Throwable t) {
            /*
             * If something goes wrong, then close the http connection.
             * Otherwise, the connection will be closed by the caller.
             */
            try {
                
                if (request != null)
                    request.abort();
                
                
            } catch (Throwable t2) {
                log.warn(t2); // ignored.
            }
            throw new RuntimeException(requestURL + " : " + t, t);
        }

    }
    
    @Override
    protected String getDefaultScoringRule() {

        return DefaultHostScoringRule.class.getName();

    }

    /**
     * This is used to defeat the httpd cache for the <code>counters</code>
     * servlet.
     */
    private final AtomicInteger nextValue = new AtomicInteger();
    
}