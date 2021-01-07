package com.tvd12.ezyhttp.server.jetty;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import com.tvd12.ezyfox.annotation.EzyProperty;
import com.tvd12.ezyfox.util.EzyLoggable;
import com.tvd12.ezyhttp.server.core.ApplicationEntry;
import com.tvd12.ezyhttp.server.core.annotation.ApplicationBootstrap;
import com.tvd12.ezyhttp.server.core.servlet.BlockingServlet;

import lombok.Setter;

@ApplicationBootstrap
public class JettyApplicationBootstrap extends EzyLoggable implements ApplicationEntry {
	
	@EzyProperty("server.port")
	@Setter
	protected int port = 8080;
	
	@EzyProperty("server.host")
	@Setter
	protected String host = "0.0.0.0";
	
	@EzyProperty("server.max_threads")
	@Setter
	protected int maxThreads = 256;
	
	@EzyProperty("server.min_threads")
	@Setter
	protected int minThreads = 16;
	
	@EzyProperty("server.idle_timeout")
	@Setter
	protected int idleTimeout = 150 * 1000;
	
	@Setter
	@EzyProperty("cors.enable")
	protected boolean corsEnable;
	
	protected Server server;
	 
    public void start() throws Exception {
    	QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, minThreads, idleTimeout);
        server = new Server(threadPool);
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(host);
        connector.setPort(port);
        server.setConnectors(new Connector[] {connector});
        ServletHandler servletHandler = newServletHandler();
        server.setHandler(servletHandler);
        server.start();
        logger.info("http server started on: {}:{}", host, port);
    }
	
    protected ServletHandler newServletHandler() {
    	ServletHandler servletHandler = new ServletHandler();
        servletHandler.addServletWithMapping(BlockingServlet.class, "/*");
        if(corsEnable) {
	        FilterHolder crossOriginFilter = newCrossOriginFilter();
	        addFilter(servletHandler, crossOriginFilter);
        }
        return servletHandler;
    }
    
    protected void addFilter(ServletHandler servletHandler, FilterHolder filter) {
    	String filerName = filter.getName();
    	servletHandler.addFilter(filter, createFilterMapping("/*", filerName));
    }
    
    protected FilterMapping createFilterMapping(String pathSpec, String filterName) {
        FilterMapping filterMapping = new FilterMapping();
        filterMapping.setPathSpec(pathSpec);
        filterMapping.setFilterName(filterName);
        return filterMapping;
    }
    
    protected FilterHolder newCrossOriginFilter() {
    	FilterHolder filter = new FilterHolder();
    	filter.setName("cross-origin");
    	filter.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
    	filter.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,HEAD,POST,PUT,DELETE,CONNECT,TRACE,PATCH,OPTIONS");
    	filter.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "*");
    	filter.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
    	filter.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_METHODS_HEADER, "GET,HEAD,POST,PUT,DELETE,CONNECT,TRACE,PATCH,OPTIONS");
    	filter.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_HEADERS_HEADER, "*");
    	CrossOriginFilter corsFilter = new CrossOriginFilter();
    	filter.setFilter(corsFilter);
    	return filter;
    }
}
