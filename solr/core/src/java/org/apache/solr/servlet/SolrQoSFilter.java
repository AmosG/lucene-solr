/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.servlet;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.apache.solr.common.params.QoSParams;
import org.apache.solr.common.util.SysStats;
import org.eclipse.jetty.servlets.QoSFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// still working out the best way for this to work
public class SolrQoSFilter extends QoSFilter {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  static final String MAX_REQUESTS_INIT_PARAM = "maxRequests";
  static final String SUSPEND_INIT_PARAM = "suspendMs";
  static final int PROC_COUNT = ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors();
  public static final int OUR_LOAD_HIGH = 99;
  protected int _origMaxRequests;


  private static SysStats sysStats = SysStats.getSysStats();

  @Override
  public void init(FilterConfig filterConfig) {
    super.init(filterConfig);
    _origMaxRequests = Integer.getInteger("solr.concurrentRequests.max", 1000);
    super.setMaxRequests(_origMaxRequests);
    super.setSuspendMs(Integer.getInteger("solr.concurrentRequests.suspendms", 15000));
    super.setWaitMs(Integer.getInteger("solr.concurrentRequests.waitms", 2000));
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    String source = req.getHeader(QoSParams.REQUEST_SOURCE);
    boolean imagePath = req.getPathInfo() != null && req.getPathInfo().startsWith("/img/");
    if (!imagePath && (source == null || !source.equals(QoSParams.INTERNAL))) {

      // TODO - we don't need to call this *every* request
      double ourLoad = sysStats.getAvarageUsagePerCPU();
      if (ourLoad > OUR_LOAD_HIGH) {
        log.info("Our individual load is {}", ourLoad);
        int cMax = getMaxRequests();
        if (cMax > 2) {
          int max = Math.max(2, (int) ((double)cMax * 0.60D));
          log.info("set max concurrent requests to {}", max);
          setMaxRequests(max);
        }
      } else {
        // nocommit - deal with no supported, use this as a fail safe with high and low watermark?
        double load =  ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        if (load < 0) {
          log.warn("SystemLoadAverage not supported on this JVM");
          load = 0;
        }
        double sLoad = load / (double) PROC_COUNT;
        if (sLoad > PROC_COUNT) {
          int cMax = getMaxRequests();
          if (cMax > 2) {
            int max = Math.max(2, (int) ((double) cMax * 0.60D));
            log.info("set max concurrent requests to {}", max);
            setMaxRequests(max);
          }
        } else if (sLoad < PROC_COUNT && _origMaxRequests != getMaxRequests()) {

          log.info("set max concurrent requests to orig value {}", _origMaxRequests);
          setMaxRequests(_origMaxRequests);
        }
        //if (log.isDebugEnabled()) log.debug("external request, load:" + sLoad); //nocommit: remove when testing is done
        log.info("external request, load:" + sLoad);
      }

      super.doFilter(req, response, chain);

    } else {
      //if (log.isDebugEnabled()) log.debug("internal request"); //nocommit: remove when testing is done
      log.info("internal request, allow");
      chain.doFilter(req, response);
    }
  }
}