package com.tvd12.ezyhttp.server.core.servlet;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tvd12.ezyfox.io.EzyStrings;
import com.tvd12.ezyhttp.core.codec.BodySerializer;
import com.tvd12.ezyhttp.core.codec.DataConverters;
import com.tvd12.ezyhttp.core.constant.ContentTypes;
import com.tvd12.ezyhttp.core.constant.HttpMethod;
import com.tvd12.ezyhttp.core.data.MultiValueMap;
import com.tvd12.ezyhttp.core.exception.DeserializeValueException;
import com.tvd12.ezyhttp.core.exception.HttpBadRequestException;
import com.tvd12.ezyhttp.core.exception.HttpRequestException;
import com.tvd12.ezyhttp.core.response.ResponseEntity;
import com.tvd12.ezyhttp.server.core.handler.RequestHandler;
import com.tvd12.ezyhttp.server.core.handler.UncaughtExceptionHandler;
import com.tvd12.ezyhttp.server.core.interceptor.RequestInterceptor;
import com.tvd12.ezyhttp.server.core.manager.ComponentManager;
import com.tvd12.ezyhttp.server.core.manager.ExceptionHandlerManager;
import com.tvd12.ezyhttp.server.core.manager.InterceptorManager;
import com.tvd12.ezyhttp.server.core.manager.RequestHandlerManager;
import com.tvd12.ezyhttp.server.core.request.RequestArguments;
import com.tvd12.ezyhttp.server.core.request.SimpleRequestArguments;

public class BlockingServlet extends HttpServlet {
	private static final long serialVersionUID = -3874017929628817672L;

	protected DataConverters dataConverters;
	protected ComponentManager componentManager;
	protected InterceptorManager interceptorManager;
	protected RequestHandlerManager requestHandlerManager;
	protected ExceptionHandlerManager exceptionHandlerManager;
	
	protected final Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
	public void init() throws ServletException {
		this.componentManager = ComponentManager.getInstance();
		this.dataConverters = componentManager.getDataConverters();
		this.interceptorManager = componentManager.getInterceptorManager();
		this.requestHandlerManager = componentManager.getRequestHandlerManager();
		this.exceptionHandlerManager = componentManager.getExceptionHandlerManager();
	}
	
	@Override
	protected void doGet(
			HttpServletRequest request, 
			HttpServletResponse response) throws ServletException, IOException {
		handleRequest(HttpMethod.GET, request, response);
	}
	
	@Override
	protected void doPost(
			HttpServletRequest request, 
			HttpServletResponse response) throws ServletException, IOException {
		handleRequest(HttpMethod.POST, request, response);
	}
	
	@Override
	protected void doPut(
			HttpServletRequest request, 
			HttpServletResponse response) throws ServletException, IOException {
		handleRequest(HttpMethod.PUT, request, response);
	}
	
	@Override
	protected void doDelete(
			HttpServletRequest request, 
			HttpServletResponse response) throws ServletException, IOException {
		handleRequest(HttpMethod.DELETE, request, response);
	}
	
	protected void handleRequest(
			HttpMethod method,
			HttpServletRequest request, 
			HttpServletResponse response) throws ServletException, IOException {
		String requestURI = request.getRequestURI();
		boolean hasHandler = requestHandlerManager.hasHandler(requestURI);
		if(!hasHandler) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			responseString(response, "uri " + requestURI + " not found");
			return;
		}
		RequestHandler requestHandler = requestHandlerManager.getHandler(method, requestURI);
		if(requestHandler == null) {
			response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			responseString(response, "method " + method + " not allowed");
			return;
		}
		boolean acceptableRequest = false;
		String uriTemplate = requestHandler.getRequestURI();
		RequestArguments arguments = newRequestArguments(method, uriTemplate, request, response);
		try {
			acceptableRequest = preHandleRequest(arguments, requestHandler);
			if(acceptableRequest) {
				Object responseData = requestHandler.handle(arguments);
				if(responseData != null) {
					if(responseData == ResponseEntity.ASYNC) {
						request.startAsync();
					}
					else {
						String responseContentType = requestHandler.getResponseContentType();
						handleResponseData(response, responseContentType, responseData);
					}
				}
			}
			else {
				response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
			}
		}
		catch (Exception e) {
			handleException(request, response, e);
		}
		finally {
			arguments.release();
		}
		if(acceptableRequest)
			postHandleRequest(arguments, requestHandler);
	}
	
	protected void handleException(
			HttpServletRequest request,
			HttpServletResponse response, Exception e) throws IOException {
		Class<?> exceptionClass = e.getClass();
		UncaughtExceptionHandler handler = 
				exceptionHandlerManager.getUncaughtExceptionHandler(exceptionClass);
		Exception exception = e;
		if(handler != null) {
			try {
				Object result = handler.handleException(e);
				if(result != null) {
					String responseContentType = handler.getResponseContentType();
					handleResponseData(response, responseContentType, result);
				}
				exception = null;
			}
			catch (Exception ex) {
				exception = ex;
			}
		}
		if(exception != null) {
			if(exception instanceof DeserializeValueException) {
				DeserializeValueException deException = (DeserializeValueException)exception;
				Map<String, String> badData = new HashMap<>();
				badData.put(deException.getName(), "invalid");
				badData.put("exception", exception.getClass().getName());
				exception = new HttpBadRequestException(badData);
			}
			if(exception instanceof HttpRequestException) {
				HttpRequestException requestException = (HttpRequestException)exception;
				int errorStatus = requestException.getCode();
				Object errorData = requestException.getData();
				ResponseEntity errorResponse = ResponseEntity.create(errorStatus, errorData);
				if(errorData != null) {
					try {
						handleResponseData(response, ContentTypes.APPLICATION_JSON, errorResponse);
						exception = null;
					}
					catch (Exception ex) {
						exception = ex;
					}
				}
			}
		}
		if(exception != null) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			logger.warn("handle request uri: {} error", request.getRequestURI(), exception);
		}
	}
	
	protected boolean preHandleRequest(
			RequestArguments arguments, 
			RequestHandler requestHandler) throws Exception {
		Method handler = requestHandler.getHandlerMethod();
		for(RequestInterceptor interceptor : interceptorManager.getRequestInterceptors()) {
			boolean passed = interceptor.preHandle(arguments, handler);
			if(!passed)
				return false;
		}
		return true;
	}
	
	protected void postHandleRequest(
			RequestArguments arguments, RequestHandler requestHandler) {
		Method handler = requestHandler.getHandlerMethod();
		for(RequestInterceptor interceptor : interceptorManager.getRequestInterceptors())
			interceptor.postHandle(arguments, handler);
	}
	
	protected void handleResponseData(
			HttpServletResponse response, 
			String contentType, Object data) throws Exception {
		response.setContentType(contentType);
		Object body = data;
		if(body instanceof ResponseEntity) {
			ResponseEntity entity = (ResponseEntity)body;
			body = entity.getBody();
			response.setStatus(entity.getStatus());
			MultiValueMap headers = entity.getHeaders();
			if(headers != null) {
				Map<String, String> encodedHeaders = headers.toMap();
				for(Entry<String, String> entry : encodedHeaders.entrySet())
					response.addHeader(entry.getKey(), entry.getValue());
			}
		}
		else {
			response.setStatus(HttpServletResponse.SC_OK);
		}
		if(body != null)
			responseBody(response, body);
	}
	
	protected void responseBody(
			HttpServletResponse response, Object data) throws IOException {
		String contentType = response.getContentType();
		BodySerializer bodySerializer = dataConverters.getBodySerializer(contentType);
		if(bodySerializer == null)
			throw new IOException("has no body serializer for: " + contentType);
		byte[] bytes = bodySerializer.serialize(data);
		responseBytes(response, bytes);
	}
	
	protected void responseString(
			HttpServletResponse response, String str) throws IOException {
		byte[] bytes = EzyStrings.getUtfBytes(str);
		responseBytes(response, bytes);
	}
	
	protected void responseBytes(
			HttpServletResponse response, byte[] bytes) throws IOException {
		response.setContentLength(bytes.length);
		ServletOutputStream outputStream = response.getOutputStream();
		outputStream.write(bytes);
	}
	
	protected RequestArguments newRequestArguments(
			HttpMethod method,
			String uriTemplate,
			HttpServletRequest request, 
			HttpServletResponse response) {
		SimpleRequestArguments arguments = new SimpleRequestArguments();
		arguments.setMethod(method);
		arguments.setRequest(request);
		arguments.setResponse(response);
		arguments.setUriTemplate(uriTemplate);
		
		Enumeration<String> paramNames = request.getParameterNames();
		while(paramNames.hasMoreElements()) {
			String paramName = paramNames.nextElement();
			String paramValue = request.getParameter(paramName);
			arguments.setParameter(paramName, paramValue);
		}
		
		Enumeration<String> headerNames = request.getHeaderNames();
		while(headerNames.hasMoreElements()) {
			String headerName = headerNames.nextElement();
			String headerValue = request.getHeader(headerName);
			arguments.setHeader(headerName, headerValue);
		}
		
		return arguments;
	}

}
