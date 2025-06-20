//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.FilterRegistration.Dynamic;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.server.AliasCheck;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.handler.ContextHandler.ScopedContext;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.eclipse.jetty.util.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ContextHandler.
 *
 * <p>
 * This handler wraps a call to handle by setting the context and servlet path, plus setting the context classloader.
 * </p>
 * <p>
 * If the context init parameter {@code org.eclipse.jetty.server.context.ManagedAttributes} is set to a comma separated list of names, then they are treated as
 * context attribute names, which if set as attributes are passed to the servers Container so that they may be managed with JMX.
 * </p>
 * <p>
 * The maximum size of a form that can be processed by this context is controlled by the system properties {@code org.eclipse.jetty.server.Request.maxFormKeys} and
 * {@code org.eclipse.jetty.server.Request.maxFormContentSize}. These can also be configured with {@link #setMaxFormContentSize(int)} and {@link #setMaxFormKeys(int)}
 * </p>
 * <p>
 * The executor is made available via a context attributed {@code org.eclipse.jetty.server.Executor}.
 * </p>
 * <p>
 * By default, the context is created with the {@link AllowedResourceAliasChecker} which is configured to allow symlinks. If
 * this alias checker is not required, then {@link #clearAliasChecks()} or {@link #setAliasChecks(List)} should be called.
 * </p>
 * This handler can be invoked in 2 different ways:
 * <ul>
 *  <li>
 *      If this is added directly as a {@link Handler} on the {@link Server} this will supply the {@link CoreContextHandler}
 *      associated with this {@link ContextHandler}. This will wrap the request to a {@link CoreContextRequest} and fall
 *      through to the {@code CoreToNestedHandler} which invokes the {@link HttpChannel} and this will eventually reach
 *      {@link ContextHandler#handle(String, Request, HttpServletRequest, HttpServletResponse)}.
 *  </li>
 *  <li>
 *      If this is nested inside another {@link ContextHandler} and not added directly to the server then its
 *      {@link CoreContextHandler} will never be added to the server. However it will still be created and its
 *      {@link ScopedContext} will be used to enter scope.
 *   </li>
 * </ul>
 */
@ManagedObject("EE9 Context")
public class ContextHandler extends ScopedHandler implements Attributes, Supplier<Handler>
{
    public static final Environment ENVIRONMENT = Environment.ensure("ee9");
    public static final int SERVLET_MAJOR_VERSION = 5;
    public static final int SERVLET_MINOR_VERSION = 0;
    public static final Class<?>[] SERVLET_LISTENER_TYPES =
            {
                    ServletContextListener.class,
                    ServletContextAttributeListener.class,
                    ServletRequestListener.class,
                    ServletRequestAttributeListener.class,
                    HttpSessionIdListener.class,
                    HttpSessionListener.class,
                    HttpSessionAttributeListener.class
            };

    public static final int DEFAULT_LISTENER_TYPE_INDEX = 1;

    public static final int EXTENDED_LISTENER_TYPE_INDEX = 0;

    private static final String UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER = "Unimplemented {} - use org.eclipse.jetty.servlet.ServletContextHandler";

    private static final Logger LOG = LoggerFactory.getLogger(ContextHandler.class);

    private static final ThreadLocal<APIContext> __context = new ThreadLocal<>();

    private static String __serverInfo = "jetty/" + Server.getVersion();

    public static final String MAX_FORM_KEYS_KEY = FormFields.MAX_FIELDS_ATTRIBUTE;
    public static final String MAX_FORM_CONTENT_SIZE_KEY = FormFields.MAX_LENGTH_ATTRIBUTE;
    public static final int DEFAULT_MAX_FORM_KEYS = FormFields.MAX_FIELDS_DEFAULT;
    public static final int DEFAULT_MAX_FORM_CONTENT_SIZE = FormFields.MAX_LENGTH_DEFAULT;
    private boolean _canonicalEncodingURIs = false;
    private boolean _usingSecurityManager = getSecurityManager() != null;

    /**
     * Get the current ServletContext implementation.
     *
     * @return ServletContext implementation
     */
    public static APIContext getCurrentContext()
    {
        return __context.get();
    }

    public static ContextHandler getCurrentContextHandler()
    {
        APIContext c = getCurrentContext();
        if (c != null)
            return c.getContextHandler();
        return null;
    }

    public static ContextHandler getContextHandler(ServletContext context)
    {
        if (context instanceof APIContext)
            return ((APIContext)context).getContextHandler();
        return getCurrentContextHandler();
    }

    public static ServletContext getServletContext(Context context)
    {
        if (context instanceof CoreContextHandler.CoreContext coreContext)
            return coreContext.getAPIContext();
        return null;
    }

    public enum ContextStatus
    {
        NOTSET,
        INITIALIZED,
        DESTROYED
    }

    private final CoreContextHandler _coreContextHandler;
    protected ContextStatus _contextStatus = ContextStatus.NOTSET;
    protected APIContext _apiContext;
    private final Map<String, String> _initParams;
    private String _defaultRequestCharacterEncoding;
    private String _defaultResponseCharacterEncoding;
    private String _contextPathEncoded = "/";
    private Map<String, String> _localeEncodingMap;
    private String[] _welcomeFiles;
    private ErrorHandler _errorHandler;
    private Logger _logger;
    private int _maxFormKeys = Integer.getInteger(MAX_FORM_KEYS_KEY, DEFAULT_MAX_FORM_KEYS);
    private int _maxFormContentSize = Integer.getInteger(MAX_FORM_CONTENT_SIZE_KEY, DEFAULT_MAX_FORM_CONTENT_SIZE);
    private boolean _compactPath = false;

    private final List<EventListener> _programmaticListeners = new CopyOnWriteArrayList<>();
    private final List<ServletContextListener> _servletContextListeners = new CopyOnWriteArrayList<>();
    private final List<ServletContextListener> _destroyServletContextListeners = new ArrayList<>();
    private final List<ServletContextAttributeListener> _servletContextAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestListener> _servletRequestListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestAttributeListener> _servletRequestAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<ContextScopeListener> _contextListeners = new CopyOnWriteArrayList<>();
    private final Set<EventListener> _durableListeners = new HashSet<>();

    public ContextHandler()
    {
        this(null, null, null);
        Objects.requireNonNull(ENVIRONMENT);
    }

    public ContextHandler(String contextPath)
    {
        this(null, null, contextPath);
    }

    public ContextHandler(String contextPath, org.eclipse.jetty.ee9.nested.Handler handler)
    {
        this(contextPath);
        setHandler(handler);
    }

    public ContextHandler(org.eclipse.jetty.server.Handler.Container parent)
    {
        this(null, parent, "/");
    }

    public ContextHandler(org.eclipse.jetty.server.Handler.Container parent, String contextPath)
    {
        this(null, parent, contextPath);
    }

    protected ContextHandler(APIContext context,
                             org.eclipse.jetty.server.Handler.Container parent,
                             String contextPath)
    {
        _coreContextHandler = new CoreContextHandler();
        installBean(_coreContextHandler, false);
        _apiContext = context == null ? new APIContext() : context;
        _initParams = new HashMap<>();
        if (contextPath != null)
            setContextPath(contextPath);
        HandlerWrapper.setAsParent(parent, _coreContextHandler);
    }

    @Override
    public Handler get()
    {
        return _coreContextHandler;
    }

    public CoreContextHandler getCoreContextHandler()
    {
        return _coreContextHandler;
    }

    /**
     * Insert a handler between the {@link #getCoreContextHandler()} and this handler.
     * @param coreHandler A core handler to insert
     */
    public void insertHandler(org.eclipse.jetty.server.Handler.Singleton coreHandler)
    {
        getCoreContextHandler().insertHandler(coreHandler);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            Dumpable.named("maxFormKeys ", getMaxFormKeys()),
            Dumpable.named("maxFormContentSize ", getMaxFormContentSize()),
            new DumpableCollection("initparams " + this, getInitParams().entrySet()));
    }

    public APIContext getServletContext()
    {
        return _apiContext;
    }

    /**
     * @return the allowNullPathInfo true if /context is not redirected to /context/
     */
    @ManagedAttribute("Checks if the /context is not redirected to /context/")
    public boolean getAllowNullPathInfo()
    {
        return _coreContextHandler.getAllowNullPathInContext();
    }

    /**
     * Set true if /context is not redirected to /context/.
     * @param allowNullPathInfo true if /context is not redirected to /context/
     */
    public void setAllowNullPathInfo(boolean allowNullPathInfo)
    {
        _coreContextHandler.setAllowNullPathInContext(allowNullPathInfo);
    }

    /**
     * Cross context dispatch support.
     * @param supported {@code True} if cross context dispatch is supported
     * @see org.eclipse.jetty.server.handler.ContextHandler#setCrossContextDispatchSupported(boolean)
     */
    public void setCrossContextDispatchSupported(boolean supported)
    {
        getCoreContextHandler().setCrossContextDispatchSupported(supported);
    }

    /**
     * Cross context dispatch support.
     * @return {@code True} if cross context dispatch is supported
     * @see org.eclipse.jetty.server.handler.ContextHandler#isCrossContextDispatchSupported()
     */
    public boolean isCrossContextDispatchSupported()
    {
        return getCoreContextHandler().isCrossContextDispatchSupported();
    }

    @Override
    public void setServer(Server server)
    {
        super.setServer(server);
        if (!Objects.equals(server, _coreContextHandler.getServer()))
            _coreContextHandler.setServer(server);
        if (_errorHandler != null)
            _errorHandler.setServer(server);
    }

    public boolean isUsingSecurityManager()
    {
        return _usingSecurityManager;
    }

    public void setUsingSecurityManager(boolean usingSecurityManager)
    {
        if (usingSecurityManager && getSecurityManager() == null)
            throw new IllegalStateException("No security manager");
        _usingSecurityManager = usingSecurityManager;
    }

    public void setVirtualHosts(String[] vhosts)
    {
        _coreContextHandler.setVirtualHosts(vhosts == null ? Collections.emptyList() : Arrays.asList(vhosts));
    }

    public void addVirtualHosts(String[] virtualHosts)
    {
        _coreContextHandler.addVirtualHosts(virtualHosts);
    }

    public void removeVirtualHosts(String[] virtualHosts)
    {
        _coreContextHandler.removeVirtualHosts(virtualHosts);
    }

    @ManagedAttribute(value = "Virtual hosts accepted by the context", readonly = true)
    public String[] getVirtualHosts()
    {
        return _coreContextHandler.getVirtualHosts().toArray(new String[0]);
    }

    @Override
    public Object getAttribute(String name)
    {
        return _coreContextHandler.getAttribute(name);
    }

    public Enumeration<String> getAttributeNames()
    {
        return Collections.enumeration(getAttributeNameSet());
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return _coreContextHandler.getAttributeNameSet();
    }

    /**
     * @return Returns the attributes.
     */
    public Attributes getAttributes()
    {
        return _coreContextHandler;
    }

    /**
     * @return Returns the classLoader.
     */
    public ClassLoader getClassLoader()
    {
        return _coreContextHandler.getClassLoader();
    }

    /**
     * Make best effort to extract a file classpath from the context classloader
     *
     * @return Returns the classLoader.
     */
    @ManagedAttribute("The file classpath")
    public String getClassPath()
    {
        return _coreContextHandler.getClassPath();
    }

    /**
     * @return Returns the contextPath.
     */
    @ManagedAttribute("True if URLs are compacted to replace the multiple '/'s with a single '/'")
    public String getContextPath()
    {
        return _coreContextHandler.getContextPath();
    }

    /**
     * @return Returns the encoded contextPath.
     */
    public String getContextPathEncoded()
    {
        return _contextPathEncoded;
    }

    /**
     * Get the context path in a form suitable to be returned from {@link HttpServletRequest#getContextPath()}
     * or {@link ServletContext#getContextPath()}.
     *
     * @return Returns the encoded contextPath, or empty string for root context
     */
    public String getRequestContextPath()
    {
        String contextPathEncoded = getContextPathEncoded();
        return "/".equals(contextPathEncoded) ? "" : contextPathEncoded;
    }

    /*
     * @see jakarta.servlet.ServletContext#getInitParameter(java.lang.String)
     */
    public String getInitParameter(String name)
    {
        return _initParams.get(name);
    }

    public String setInitParameter(String name, String value)
    {
        return _initParams.put(name, value);
    }

    /*
     * @see jakarta.servlet.ServletContext#getInitParameterNames()
     */
    public Enumeration<String> getInitParameterNames()
    {
        return Collections.enumeration(_initParams.keySet());
    }

    /**
     * @return Returns the initParams.
     */
    @ManagedAttribute("Initial Parameter map for the context")
    public Map<String, String> getInitParams()
    {
        return _initParams;
    }

    /*
     * @see jakarta.servlet.ServletContext#getServletContextName()
     */
    @ManagedAttribute(value = "Display name of the Context", readonly = true)
    public String getDisplayName()
    {
        return _coreContextHandler.getDisplayName();
    }

    /**
     * Add a context event listeners.
     *
     * @param listener the event listener to add
     * @return true if the listener was added
     * @see ContextScopeListener
     * @see ServletContextListener
     * @see ServletContextAttributeListener
     * @see ServletRequestListener
     * @see ServletRequestAttributeListener
     */
    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (super.addEventListener(listener))
        {
            if (listener instanceof ContextScopeListener contextScopeListener)
            {
                _contextListeners.add(contextScopeListener);
                if (__context.get() != null)
                    contextScopeListener.enterScope(__context.get(), null, "Listener registered");
            }

            if (listener instanceof ServletContextListener servletContextListener)
            {
                if (_contextStatus == ContextStatus.INITIALIZED)
                {
                    _destroyServletContextListeners.add(servletContextListener);
                    if (isStarting())
                    {
                        LOG.warn("ContextListener {} added whilst starting {}", servletContextListener, this);
                        callContextInitialized(servletContextListener, new ServletContextEvent(_apiContext));
                    }
                    else
                    {
                        LOG.warn("ContextListener {} added after starting {}", servletContextListener, this);
                    }
                }

                _servletContextListeners.add((ServletContextListener)listener);
            }

            if (listener instanceof ServletContextAttributeListener servletContextAttributeListener)
                _servletContextAttributeListeners.add(servletContextAttributeListener);

            if (listener instanceof ServletRequestListener servletRequestListener)
                _servletRequestListeners.add(servletRequestListener);

            if (listener instanceof ServletRequestAttributeListener servletRequestAttributeListener)
                _servletRequestAttributeListeners.add(servletRequestAttributeListener);

            return true;
        }
        return false;
    }

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if (super.removeEventListener(listener))
        {
            if (listener instanceof ContextScopeListener)
                _contextListeners.remove(listener);

            if (listener instanceof ServletContextListener)
            {
                _servletContextListeners.remove(listener);
                _destroyServletContextListeners.remove(listener);
            }

            if (listener instanceof ServletContextAttributeListener)
                _servletContextAttributeListeners.remove(listener);

            if (listener instanceof ServletRequestListener)
                _servletRequestListeners.remove(listener);

            if (listener instanceof ServletRequestAttributeListener)
                _servletRequestAttributeListeners.remove(listener);
            return true;
        }
        return false;
    }

    /**
     * Apply any necessary restrictions on a programmatic added listener.
     *
     * @param listener the programmatic listener to add
     */
    protected void addProgrammaticListener(EventListener listener)
    {
        _programmaticListeners.add(listener);
    }

    public boolean isProgrammaticListener(EventListener listener)
    {
        return _programmaticListeners.contains(listener);
    }

    public boolean isDurableListener(EventListener listener)
    {
        // The durable listeners are those set when the context is started
        if (isStarted())
            return _durableListeners.contains(listener);
        // If we are not yet started then all set listeners are durable
        return getEventListeners().contains(listener);
    }

    /**
     * @return false if this context is unavailable (sends 503)
     */
    public boolean isAvailable()
    {
        return _coreContextHandler.isAvailable();
    }

    /**
     * Set Available status.
     *
     * @param available true to set as enabled
     */
    public void setAvailable(boolean available)
    {
        _coreContextHandler.setAvailable(available);
    }

    public Logger getLogger()
    {
        return _logger;
    }

    public void setLogger(Logger logger)
    {
        _logger = logger;
    }

    @Override
    protected void doStart() throws Exception
    {
        // If we are being started directly (rather than via a start of the CoreContextHandler),
        // then we need the LifeCycle Listener to ensure both this and the CoreContextHandler are
        // in STARTING state when doStartInContext is called.
        if (org.eclipse.jetty.server.handler.ContextHandler.getCurrentContext() != _coreContextHandler.getContext())
        {
            // Make the CoreContextHandler lifecycle responsible for calling the doStartContext() and doStopContext().
            _coreContextHandler.unmanage(this);
            _coreContextHandler.addEventListener(new LifeCycle.Listener()
            {
                @Override
                public void lifeCycleStarting(LifeCycle event)
                {
                    try
                    {
                        _coreContextHandler.getContext().call(() -> doStartInContext(), null);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void lifeCycleStarted(LifeCycle event)
                {
                    _coreContextHandler.manage(this);
                    _coreContextHandler.removeEventListener(this);
                }
            });

            _coreContextHandler.start();
            return;
        }

        _coreContextHandler.getContext().call(this::doStartInContext, null);
    }

    protected void doStartInContext() throws Exception
    {
        if (_logger == null)
            _logger = LoggerFactory.getLogger(ContextHandler.class.getName() + getLogNameSuffix());

        if (_errorHandler == null)
            setErrorHandler(new ErrorHandler());

        setAttribute("org.eclipse.jetty.server.Executor", getServer().getThreadPool());

        _durableListeners.addAll(getEventListeners());

        // allow the call to super.doStart() to be deferred by extended implementations.
        startContext();
        contextInitialized();
    }

    @Override
    protected void doStop() throws Exception
    {
        // If we are being stopped directly (rather than via a start of the CoreContextHandler),
        // then doStopInContext() will be called by the listener on the lifecycle of CoreContextHandler.
        if (org.eclipse.jetty.server.handler.ContextHandler.getCurrentContext() != _coreContextHandler.getContext())
        {
            // Make the CoreContextHandler lifecycle responsible for calling the doStartContext() and doStopContext().
            _coreContextHandler.unmanage(this);
            _coreContextHandler.addEventListener(new LifeCycle.Listener()
            {
                @Override
                public void lifeCycleStopping(LifeCycle event)
                {
                    try
                    {
                        _coreContextHandler.getContext().call(() -> doStopInContext(), null);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void lifeCycleStopped(LifeCycle event)
                {
                    _coreContextHandler.manage(this);
                    _coreContextHandler.removeEventListener(this);
                }
            });

            _coreContextHandler.stop();
            return;
        }

        _coreContextHandler.getContext().call(this::doStopInContext, null);
    }

    protected void doStopInContext() throws Exception
    {
        try
        {
            stopContext();
            contextDestroyed();

            // retain only durable listeners
            setEventListeners(_durableListeners);
            _durableListeners.clear();

            if (_errorHandler != null)
                _errorHandler.stop();

            for (EventListener l : _programmaticListeners)
            {
                removeEventListener(l);
                if (l instanceof ContextScopeListener)
                {
                    try
                    {
                        ((ContextScopeListener)l).exitScope(_apiContext, null);
                    }
                    catch (Throwable e)
                    {
                        LOG.warn("Unable to exit scope", e);
                    }
                }
            }
            _programmaticListeners.clear();
        }
        finally
        {
            _contextStatus = ContextStatus.NOTSET;
        }
    }

    private String getLogNameSuffix()
    {
        // Use display name first
        String logName = getDisplayName();
        if (StringUtil.isBlank(logName))
        {
            // try context path
            logName = getContextPath();
            if (logName != null)
            {
                // Strip prefix slash
                if (logName.startsWith("/"))
                {
                    logName = logName.substring(1);
                }
            }

            if (StringUtil.isBlank(logName))
            {
                // an empty context path is the ROOT context
                logName = "ROOT";
            }
        }

        // Replace bad characters.
        return '.' + logName.replaceAll("\\W", "_");
    }

    /**
     * Extensible startContext. this method is called from {@link ContextHandler#doStart()} instead of a call to super.doStart(). This allows derived classes to
     * insert additional handling (Eg configuration) before the call to super.doStart by this method will start contained handlers.
     *
     * @throws Exception if unable to start the context
     * @see APIContext
     */
    protected void startContext() throws Exception
    {
        String managedAttributes = _initParams.get(org.eclipse.jetty.server.handler.ContextHandler.MANAGED_ATTRIBUTES);
        if (managedAttributes != null)
            addEventListener(new ManagedAttributeListener(this, StringUtil.csvSplit(managedAttributes)));

        super.doStart();
    }

    /**
     * Call the ServletContextListeners contextInitialized methods.
     * This can be called from a ServletHandler during the proper sequence
     * of initializing filters, servlets and listeners. However, if there is
     * no ServletHandler, the ContextHandler will call this method during
     * doStart().
     */
    public void contextInitialized() throws Exception
    {
        // Call context listeners
        if (_contextStatus == ContextStatus.NOTSET)
        {
            _contextStatus = ContextStatus.INITIALIZED;
            _destroyServletContextListeners.clear();
            if (!_servletContextListeners.isEmpty())
            {
                ServletContextEvent event = new ServletContextEvent(_apiContext);
                for (ServletContextListener listener : _servletContextListeners)
                {
                    callContextInitialized(listener, event);
                    _destroyServletContextListeners.add(listener);
                }
            }
        }
    }

    /**
     * Call the ServletContextListeners with contextDestroyed.
     * This method can be called from a ServletHandler in the
     * proper sequence of destroying filters, servlets and listeners.
     * If there is no ServletHandler, the ContextHandler must ensure
     * these listeners are called instead.
     */
    public void contextDestroyed() throws Exception
    {
        switch (_contextStatus)
        {
            case INITIALIZED:
            {
                try
                {
                    //Call context listeners
                    Throwable multiException = null;
                    ServletContextEvent event = new ServletContextEvent(_apiContext);
                    Collections.reverse(_destroyServletContextListeners);
                    for (ServletContextListener listener : _destroyServletContextListeners)
                    {
                        try
                        {
                            callContextDestroyed(listener, event);
                        }
                        catch (Exception x)
                        {
                            multiException = ExceptionUtil.combine(multiException, x);
                        }
                    }
                    ExceptionUtil.ifExceptionThrow(multiException);
                }
                finally
                {
                    _contextStatus = ContextStatus.DESTROYED;
                }
                break;
            }
            default:
                break;
        }
    }

    protected void stopContext() throws Exception
    {
        // stop all the handler hierarchy
        super.doStop();
    }

    protected void callContextInitialized(ServletContextListener l, ServletContextEvent e)
    {
        if (getServer().isDryRun())
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("contextInitialized: {}->{}", e, l);
        l.contextInitialized(e);
    }

    protected void callContextDestroyed(ServletContextListener l, ServletContextEvent e)
    {
        if (getServer().isDryRun())
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("contextDestroyed: {}->{}", e, l);
        l.contextDestroyed(e);
    }

    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("scope {}|{}|{} @ {}", baseRequest.getContextPath(), baseRequest.getServletPath(), baseRequest.getPathInfo(), this);

        APIContext oldContext = baseRequest.getContext();
        String oldPathInContext = baseRequest.getPathInContext();
        String pathInContext = target;
        DispatcherType dispatch = baseRequest.getDispatcherType();

        // Are we already in this context?
        if (oldContext != _apiContext)
        {
            // check the target.
            String contextPath = getContextPath();
            if (DispatcherType.REQUEST.equals(dispatch) || DispatcherType.ASYNC.equals(dispatch) || baseRequest.getCoreRequest().getContext().isCrossContextDispatch(baseRequest.getCoreRequest()))
            {
                // Perform context-path (and url-pattern) matching on compacted path.
                if (isCompactPath())
                    target = URIUtil.compactPath(target);

                if (target.length() > contextPath.length())
                {
                    if (contextPath.length() > 1)
                        target = target.substring(contextPath.length());
                    pathInContext = target;
                }
                else if (contextPath.length() == 1)
                {
                    target = "/";
                    pathInContext = "/";
                }
                else
                {
                    target = "/";
                    pathInContext = null;
                }
            }
        }

        try
        {
            baseRequest.setContext(_apiContext,
                (DispatcherType.INCLUDE.equals(dispatch) || !target.startsWith("/")) ? oldPathInContext : pathInContext);

            ScopedContext context = getCoreContextHandler().getContext();
            if (context == org.eclipse.jetty.server.handler.ContextHandler.getCurrentContext())
            {
                nextScope(target, baseRequest, request, response);
            }
            else
            {
                String t = target;
                context.call(() -> nextScope(t, baseRequest, request, response), baseRequest.getCoreRequest());
            }
        }
        catch (IOException | ServletException | RuntimeException e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new ServletException("Unexpected Exception", t);
        }
        finally
        {
            baseRequest.setContext(oldContext, oldPathInContext);
        }
    }

    protected void requestInitialized(Request baseRequest, HttpServletRequest request)
    {
        // Handle the REALLY SILLY request events!
        if (!_servletRequestAttributeListeners.isEmpty())
            for (ServletRequestAttributeListener l : _servletRequestAttributeListeners)
            {
                baseRequest.addEventListener(l);
            }

        if (!_servletRequestListeners.isEmpty())
        {
            final ServletRequestEvent sre = new ServletRequestEvent(_apiContext, request);
            for (ServletRequestListener l : _servletRequestListeners)
            {
                l.requestInitialized(sre);
            }
        }
    }

    protected void requestDestroyed(Request baseRequest, HttpServletRequest request)
    {
        // Handle more REALLY SILLY request events!
        if (!_servletRequestListeners.isEmpty())
        {
            final ServletRequestEvent sre = new ServletRequestEvent(_apiContext, request);
            for (ListIterator<ServletRequestListener> i = TypeUtil.listIteratorAtEnd(_servletRequestListeners); i.hasPrevious();)
            {
                i.previous().requestDestroyed(sre);
            }
        }

        if (!_servletRequestAttributeListeners.isEmpty())
        {
            for (ListIterator<ServletRequestAttributeListener> i = TypeUtil.listIteratorAtEnd(_servletRequestAttributeListeners); i.hasPrevious();)
            {
                baseRequest.removeEventListener(i.previous());
            }
        }
    }

    @Override
    public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        final DispatcherType dispatch = baseRequest.getDispatcherType();
        final boolean new_context = dispatch == DispatcherType.REQUEST;
        try
        {
            if (new_context)
                requestInitialized(baseRequest, request);

            if (new_context && _coreContextHandler.isProtectedTarget(target))
            {
                baseRequest.setHandled(true);
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            nextHandle(target, baseRequest, request, response);
        }
        finally
        {
            if (new_context)
                requestDestroyed(baseRequest, request);
        }
    }

    /**
     * @param request A request that is applicable to the scope, or null
     * @param reason An object that indicates the reason the scope is being entered.
     */
    protected void enterScope(Request request, Object reason)
    {
        if (!_contextListeners.isEmpty())
        {
            for (ContextScopeListener listener : _contextListeners)
            {
                try
                {
                    listener.enterScope(_apiContext, request, reason);
                }
                catch (Throwable e)
                {
                    LOG.warn("Unable to enter scope", e);
                }
            }
        }
    }

    /**
     * @param request A request that is applicable to the scope, or null
     */
    protected void exitScope(Request request)
    {
        if (!_contextListeners.isEmpty())
        {
            for (ListIterator<ContextScopeListener> i = TypeUtil.listIteratorAtEnd(_contextListeners); i.hasPrevious();)
            {
                try
                {
                    i.previous().exitScope(_apiContext, request);
                }
                catch (Throwable e)
                {
                    LOG.warn("Unable to exit scope", e);
                }
            }
        }
    }

    /**
     * Handle a runnable in the scope of this context and a particular request
     *
     * @param request The request to scope the thread to (may be null if no particular request is in scope)
     * @param runnable The runnable to run.
     */
    public void handle(Request request, Runnable runnable)
    {
        APIContext oldContext = __context.get();

        // Are we already in the scope?
        if (oldContext == _apiContext)
        {
            runnable.run();
            return;
        }

        // Nope, so enter the scope and then exit
        try
        {
            __context.set(_apiContext);
            _apiContext._coreContext.run(runnable, request.getHttpChannel().getCoreRequest());
        }
        finally
        {
            __context.set(null);
        }
    }

    /*
     * Handle a runnable in the scope of this context
     */
    public void handle(Runnable runnable)
    {
        handle(null, runnable);
    }

    /**
     * Check the target. Called by {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)} when a target within a context is determined. If
     * the target is protected, 404 is returned.
     *
     * @param target the target to test
     * @return true if target is a protected target
     */
    public boolean isProtectedTarget(String target)
    {
        return _coreContextHandler.isProtectedTarget(target);
    }

    /**
     * @param targets Array of URL prefix. Each prefix is in the form /path and will match either /path exactly or /path/anything
     */
    public void setProtectedTargets(String[] targets)
    {
        _coreContextHandler.setProtectedTargets(targets);
    }

    public String[] getProtectedTargets()
    {
        return _coreContextHandler.getProtectedTargets();
    }

    @Override
    public Object removeAttribute(String name)
    {
        return _coreContextHandler.removeAttribute(name);
    }

    /*
     * Set a context attribute. Attributes set via this API cannot be overridden by the ServletContext.setAttribute API. Their lifecycle spans the stop/start of
     * a context. No attribute listener events are triggered by this API.
     *
     * @see jakarta.servlet.ServletContext#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public Object setAttribute(String name, Object value)
    {
        return _coreContextHandler.setAttribute(name, value);
    }

    /**
     * @param attributes The attributes to set.
     */
    public void setAttributes(Attributes attributes)
    {
        _coreContextHandler.clearAttributes();
        for (String n : attributes.getAttributeNameSet())
            _coreContextHandler.setAttribute(n, attributes.getAttribute(n));
    }

    @Override
    public void clearAttributes()
    {
        _coreContextHandler.clearAttributes();
    }

    /**
     * @param classLoader The classLoader to set.
     */
    public void setClassLoader(ClassLoader classLoader)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _coreContextHandler.setClassLoader(classLoader);
    }

    public void setDefaultRequestCharacterEncoding(String encoding)
    {
        _defaultRequestCharacterEncoding = encoding;
    }

    public String getDefaultRequestCharacterEncoding()
    {
        return _defaultRequestCharacterEncoding;
    }

    public void setDefaultResponseCharacterEncoding(String encoding)
    {
        _defaultResponseCharacterEncoding = encoding;
    }

    public String getDefaultResponseCharacterEncoding()
    {
        return _defaultResponseCharacterEncoding;
    }

    /**
     * @param contextPath The _contextPath to set.
     */
    public void setContextPath(String contextPath)
    {
        if (contextPath == null)
            throw new IllegalArgumentException("null contextPath");

        if (contextPath.endsWith("/*"))
        {
            LOG.warn("{} contextPath ends with /*", this);
            contextPath = contextPath.substring(0, contextPath.length() - 2);
        }
        else if (contextPath.length() > 1 && contextPath.endsWith("/"))
        {
            LOG.warn("{} contextPath ends with /", this);
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }

        if (contextPath.length() == 0)
        {
            LOG.warn("Empty contextPath");
            contextPath = "/";
        }

        _coreContextHandler.setContextPath(contextPath);

    }

    /**
     * @param displayName The servletContextName to set.
     */
    public void setDisplayName(String displayName)
    {
        _coreContextHandler.setDisplayName(displayName);
    }

    /**
     * @return Returns the resourceBase.
     */
    @ManagedAttribute("document root for context")
    public Resource getBaseResource()
    {
        return _coreContextHandler.getBaseResource();
    }

    /**
     * @return Returns the base resource as a string.
     * @deprecated use #getBaseResource()
     */
    @Deprecated
    public String getResourceBase()
    {
        Resource resourceBase = _coreContextHandler.getBaseResource();
        return resourceBase == null ? null : resourceBase.toString();
    }

    /**
     * Set the base resource for this context.
     *
     * @param base The resource used as the base for all static content of this context.
     */
    public void setBaseResource(Resource base)
    {
        _coreContextHandler.setBaseResource(base);
    }

    /**
     * Set the base resource for this context.
     *
     * @param base The resource used as the base for all static content of this context.
     * @see #setBaseResource(Resource)
     */
    public void setBaseResourceAsPath(Path base)
    {
        _coreContextHandler.setBaseResourceAsPath(base);
    }

    /**
     * Set the base resource for this context.
     *
     * @param base The resource used as the base for all static content of this context.
     * @see #setBaseResource(Resource)
     */
    public void setBaseResourceAsString(String base)
    {
        _coreContextHandler.setBaseResourceAsString(base);
    }

    /**
     * Set the base resource for this context.
     *
     * @param resourceBase A string representing the base resource for the context. Any string accepted by {@link ResourceFactory#newResource(String)} may be passed and the
     * call is equivalent to <code>setBaseResource(newResource(resourceBase));</code>
     * @deprecated use #setBaseResource
     */
    @Deprecated
    public void setResourceBase(String resourceBase)
    {
        try
        {
            setBaseResource(newResource(resourceBase));
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.warn("Unable to set baseResource: {}", resourceBase, e);
            else
                LOG.warn(e.toString());
            throw new IllegalArgumentException(resourceBase);
        }
    }

    /**
     * @return Returns the mimeTypes.
     */
    public MimeTypes.Mutable getMimeTypes()
    {
        return _coreContextHandler.getMimeTypes();
    }

    public void setWelcomeFiles(String[] files)
    {
        _welcomeFiles = files;
    }

    /**
     * @return The names of the files which the server should consider to be welcome files in this context.
     * @see <a href="http://jcp.org/aboutJava/communityprocess/final/jsr154/index.html">The Servlet Specification</a>
     * @see #setWelcomeFiles
     */
    @ManagedAttribute(value = "Partial URIs of directory welcome files", readonly = true)
    public String[] getWelcomeFiles()
    {
        return _welcomeFiles;
    }

    /**
     * @return Returns the errorHandler.
     */
    @ManagedAttribute("The error handler to use for the context")
    public ErrorHandler getErrorHandler()
    {
        return _errorHandler;
    }

    /**
     * @param errorHandler The errorHandler to set.
     */
    public void setErrorHandler(ErrorHandler errorHandler)
    {
        if (errorHandler != null)
            errorHandler.setServer(getServer());
        updateBean(_errorHandler, errorHandler, true);
        _errorHandler = errorHandler;
    }

    @ManagedAttribute("The maximum content size")
    public int getMaxFormContentSize()
    {
        return _maxFormContentSize;
    }

    /**
     * Set the maximum size of a form post, to protect against DOS attacks from large forms.
     *
     * @param maxSize the maximum size of the form content (in bytes)
     */
    public void setMaxFormContentSize(int maxSize)
    {
        _maxFormContentSize = maxSize;
    }

    public int getMaxFormKeys()
    {
        return _maxFormKeys;
    }

    /**
     * Set the maximum number of form Keys to protect against DOS attack from crafted hash keys.
     *
     * @param max the maximum number of form keys
     */
    public void setMaxFormKeys(int max)
    {
        _maxFormKeys = max;
    }

    /**
     * Is a compacted path used for context-path and url-pattern matching?
     *
     * @return True if URLs are compacted to replace multiple '/'s with a single '/'
     * @deprecated use {@code CompactPathRule} with {@code RewriteHandler} instead.  Will be removed from ee10 onwards.
     * @see URIUtil#compactPath(String)
     */
    @Deprecated(since = "10.0.5", forRemoval = true)
    public boolean isCompactPath()
    {
        return _compactPath;
    }

    /**
     * <p>
     * When performing context-path and url-pattern matching, do so with a compacted form of the
     * request path.
     * </p>
     *
     * <p>
     * Note: this compacted path is not exposed to the Servlet API, the original request path
     * is used.
     * </p>
     *
     * @param compactPath True if URLs are compacted to replace multiple '/'s with a single '/'
     * @deprecated use {@code CompactPathRule} with {@code RewriteHandler} instead.  Will be removed from ee10 onwards.
     * @see URIUtil#compactPath(String)
     */
    @Deprecated(since = "10.0.5", forRemoval = true)
    public void setCompactPath(boolean compactPath)
    {
        _compactPath = compactPath;
    }

    @Override
    public String toString()
    {
        if (_coreContextHandler == null)
            return "%s@%x.<init>".formatted(TypeUtil.toShortName(ContextHandler.class), hashCode());

        final String[] vhosts = getVirtualHosts();

        StringBuilder b = new StringBuilder();

        b.append(TypeUtil.toShortName(getClass())).append('@').append(Integer.toString(hashCode(), 16));
        b.append('{');
        if (getDisplayName() != null)
            b.append(getDisplayName()).append(',');
        b.append(getContextPath()).append(',').append(getBaseResource()).append(',').append(_coreContextHandler.isAvailable());

        if (vhosts != null && vhosts.length > 0)
            b.append(',').append(vhosts[0]);
        b.append('}');

        return b.toString();
    }

    public Class<?> loadClass(String className) throws ClassNotFoundException
    {
        if (className == null)
            return null;

        ClassLoader classLoader = _apiContext.getCoreContext().getClassLoader();
        if (classLoader == null)
            return Loader.loadClass(className);

        return classLoader.loadClass(className);
    }

    public void addLocaleEncoding(String locale, String encoding)
    {
        if (_localeEncodingMap == null)
            _localeEncodingMap = new HashMap<>();
        _localeEncodingMap.put(locale, encoding);
    }

    public String getLocaleEncoding(String locale)
    {
        if (_localeEncodingMap == null)
            return null;
        String encoding = _localeEncodingMap.get(locale);
        return encoding;
    }

    /**
     * Get the character encoding for a locale. The full locale name is first looked up in the map of encodings. If no encoding is found, then the locale
     * language is looked up.
     *
     * @param locale a <code>Locale</code> value
     * @return a <code>String</code> representing the character encoding for the locale or null if none found.
     */
    public String getLocaleEncoding(Locale locale)
    {
        if (_localeEncodingMap == null)
            return null;
        String encoding = _localeEncodingMap.get(locale.toString());
        if (encoding == null)
            encoding = _localeEncodingMap.get(locale.getLanguage());
        return encoding;
    }

    /**
     * Get all of the locale encodings
     *
     * @return a map of all the locale encodings: key is name of the locale and value is the char encoding
     */
    public Map<String, String> getLocaleEncodings()
    {
        if (_localeEncodingMap == null)
            return null;
        return Collections.unmodifiableMap(_localeEncodingMap);
    }

    /**
     * Attempt to get a Resource from the Context.
     *
     * @param pathInContext the path within the base resource to attempt to get
     * @return the resource, or null if not available.
     * @throws MalformedURLException if unable to form a Resource from the provided path
     */
    public Resource getResource(String pathInContext) throws MalformedURLException
    {
        if (pathInContext == null || !pathInContext.startsWith("/"))
            throw new MalformedURLException(pathInContext);

        Resource baseResource = _coreContextHandler.getBaseResource();
        if (baseResource == null)
            return null;

        try
        {
            // addPath with accept non-canonical paths that don't go above the root,
            // but will treat them as aliases. So unless allowed by an AliasChecker
            // they will be rejected below.
            return baseResource.resolve(pathInContext);
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
        }

        return null;
    }

    /**
     * @param path the path to check the alias for
     * @param resource the resource
     * @return True if the alias is OK
     */
    public boolean checkAlias(String path, Resource resource)
    {
        // Is the resource aliased?
        if (Resources.isReadable(resource) && resource.isAlias())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Alias resource {} for {}", resource, resource.getRealURI());

            // alias checks
            for (AliasCheck check : getAliasChecks())
            {
                if (check.checkAlias(path, resource))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Aliased resource: {} approved by {}", resource, check);
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Convert URL to Resource wrapper for {@link ResourceFactory#newResource(URL)} enables extensions to provide alternate resource implementations.
     *
     * @param url the url to convert to a Resource
     * @return the Resource for that url
     * @throws IOException if unable to create a Resource from the URL
     */
    public Resource newResource(URL url) throws IOException
    {
        return ResourceFactory.of(this).newResource(url);
    }

    /**
     * Convert URL to Resource wrapper for {@link ResourceFactory#newResource(URL)} enables extensions to provide alternate resource implementations.
     *
     * @param uri the URI to convert to a Resource
     * @return the Resource for that URI
     * @throws IOException if unable to create a Resource from the URL
     */
    public Resource newResource(URI uri) throws IOException
    {
        return ResourceFactory.of(this).newResource(uri);
    }

    /**
     * Convert a URL or path to a Resource. The default implementation is a wrapper for {@link ResourceFactory#newResource(String)}.
     *
     * @param uriOrPath The URL or path to convert
     * @return The Resource for the URL/path
     * @throws IOException The Resource could not be created.
     */
    public Resource newResource(String uriOrPath) throws IOException
    {
        return ResourceFactory.of(this).newResource(uriOrPath);
    }

    public Set<String> getResourcePaths(String path)
    {
        try
        {
            Resource resource = getResource(path);

            if (!path.endsWith("/"))
                path = path + '/';

            HashSet<String> set = new HashSet<>();

            for (Resource item: resource.list())
            {
                String entry = path + item.getFileName();
                if (item.isDirectory())
                    entry = entry + '/';
                set.add(entry);
            }
            return set;
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
        }
        return Collections.emptySet();
    }

    /**
     * Add an AliasCheck instance to possibly permit aliased resources
     *
     * @param check The alias checker
     */
    public void addAliasCheck(AliasCheck check)
    {
        _coreContextHandler.addAliasCheck(check);
    }

    /**
     * @return Immutable list of Alias checks
     */
    public List<AliasCheck> getAliasChecks()
    {
        return _coreContextHandler.getAliasChecks();
    }

    /**
     * Set list of AliasCheck instances.
     * @param checks list of AliasCheck instances
     */
    public void setAliasChecks(List<AliasCheck> checks)
    {
        _coreContextHandler.setAliasChecks(checks);
    }

    /**
     * clear the list of AliasChecks
     */
    public void clearAliasChecks()
    {
        _coreContextHandler.clearAliasChecks();
    }

    private static Object getSecurityManager()
    {
        return SecurityUtils.getSecurityManager();
    }

    /* Handle a request from a connection.
     * Called to handle a request on the connection when either the header has been received,
     * or after the entire request has been received (for short requests of known length), or
     * on the dispatch of an async request.
     */
    public void handle(HttpChannel channel) throws IOException, ServletException
    {
        final String target = channel.getRequest().getPathInfo();
        final Request request = channel.getRequest();
        final org.eclipse.jetty.ee9.nested.Response response = channel.getResponse();

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} {} ?{} on {}", request.getDispatcherType(), request.getMethod(), target, request.getQueryString(), channel);

        if (HttpMethod.OPTIONS.is(request.getMethod()) || "*".equals(target))
        {
            if (!HttpMethod.OPTIONS.is(request.getMethod()))
            {
                request.setHandled(true);
                response.sendError(HttpStatus.BAD_REQUEST_400);
            }
            else
            {
                handleOptions(request, response);
                if (!request.isHandled())
                    handle(target, request, request, response);
            }
        }
        else
            handle(target, request, request, response);

        if (LOG.isDebugEnabled())
            LOG.debug("handled={} async={} committed={} on {}", request.isHandled(), request.isAsyncStarted(), response.isCommitted(), channel);
    }

    /* Handle Options request to server
     */
    protected void handleOptions(Request request, org.eclipse.jetty.ee9.nested.Response response) throws IOException
    {
    }

    /** A request has come in via an async dispatch from a different context.
     *
     * @param channel the HttpChannel associated with the request
     * @throws IOException
     * @throws ServletException
     */
    public void handleCrossContextAsync(HttpChannel channel) throws IOException, ServletException
    {
        AsyncContextEvent event = channel.getState().getAsyncContextEvent();

        // we must mutate the request
        Request request = event.getHttpChannelState().getBaseRequest();
        HttpURI baseUri = event.getBaseURI();
        APIContext oldContext = request.getContext();
        HttpURI oldURI = request.getHttpURI();
        String oldPathInContext = request.getPathInContext();
        Fields oldQueryFields = request.getQueryFields();

        //the path in the context (encoded with possible query string)
        String encodedPathQuery = event.getDispatchPath();
        if (encodedPathQuery == null && baseUri == null)
        {
            //TODO - what would this mean?
        }
        else
        {
            try
            {
                if (encodedPathQuery == null)
                {
                    request.setHttpURI(baseUri);
                }
                else
                {
                    String encodedContextPath = URIUtil.encodePath(getContextPath());
                    if (!StringUtil.isEmpty(encodedContextPath))
                    {
                        encodedPathQuery = URIUtil.canonicalPath(URIUtil.addEncodedPaths(encodedContextPath, encodedPathQuery));
                        if (encodedPathQuery == null)
                            throw new BadMessageException(500, "Bad dispatch path");
                    }

                    if (baseUri == null)
                        baseUri = request.getHttpURI();
                    HttpURI.Mutable builder = HttpURI.build(baseUri, encodedPathQuery);

                    if (StringUtil.isEmpty(builder.getParam()))
                        builder.param(baseUri.getParam());
                    if (StringUtil.isEmpty(builder.getQuery()))
                        builder.query(baseUri.getQuery());

                    request.setHttpURI(builder);

                    if (baseUri.getQuery() != null && request.getQueryString() != null)
                        request.mergeQueryParameters(request.getHttpURI().getQuery(), request.getQueryString());
                }

                request.setContext(_apiContext, event.getDispatchPath());
                handleAsync(channel, event, request);
            }
            finally
            {
                request.setContext(oldContext, oldPathInContext);
                request.setHttpURI(oldURI);
                request.setQueryFields(oldQueryFields);
            }
        }
    }

    /* Handle a request from a connection.
     * Called to handle a request on the connection when either the header has been received,
     * or after the entire request has been received (for short requests of known length), or
     * on the dispatch of an async request.
     */
    public void handleAsync(HttpChannel channel) throws IOException, ServletException
    {
        final HttpChannelState state = channel.getRequest().getHttpChannelState();
        final AsyncContextEvent event = state.getAsyncContextEvent();
        final Request baseRequest = channel.getRequest();

        HttpURI baseUri = event.getBaseURI();
        String encodedPathQuery = event.getDispatchPath();

        if (encodedPathQuery == null && baseUri == null)
        {
            // Simple case, no request modification or merging needed
            handleAsync(channel, event, baseRequest);
            return;
        }

        // this is a dispatch with either a provided URI and/or a dispatched path
        // We will have to modify the request and then revert
        final HttpURI oldUri = baseRequest.getHttpURI();
        final Fields oldQueryParams = baseRequest.getQueryFields();
        try
        {
            if (encodedPathQuery == null)
            {
                baseRequest.setHttpURI(baseUri);
            }
            else
            {
                ServletContext servletContext = event.getServletContext();
                if (servletContext != null)
                {
                    String encodedContextPath = servletContext instanceof APIContext
                            ? ((APIContext)servletContext).getContextHandler().getContextPathEncoded()
                            : URIUtil.encodePath(servletContext.getContextPath());
                    if (!StringUtil.isEmpty(encodedContextPath))
                    {
                        encodedPathQuery = URIUtil.normalizePath(URIUtil.addEncodedPaths(encodedContextPath, encodedPathQuery));
                        if (encodedPathQuery == null)
                            throw new BadMessageException(500, "Bad dispatch path");
                    }
                }

                if (baseUri == null)
                    baseUri = oldUri;
                HttpURI.Mutable builder = HttpURI.build(baseUri, encodedPathQuery);
                if (StringUtil.isEmpty(builder.getParam()))
                    builder.param(baseUri.getParam());
                if (StringUtil.isEmpty(builder.getQuery()))
                    builder.query(baseUri.getQuery());
                baseRequest.setHttpURI(builder);

                if (baseUri.getQuery() != null && baseRequest.getQueryString() != null)
                    baseRequest.mergeQueryParameters(oldUri.getQuery(), baseRequest.getQueryString());
            }

            baseRequest.setContext(null, baseRequest.getHttpURI().getDecodedPath());
            handleAsync(channel, event, baseRequest);
        }
        finally
        {
            baseRequest.setHttpURI(oldUri);
            baseRequest.setQueryFields(oldQueryParams);
            baseRequest.resetParameters();
        }
    }

    private void handleAsync(HttpChannel channel, AsyncContextEvent event, Request baseRequest) throws IOException, ServletException
    {
        final String target = baseRequest.getPathInfo();
        final HttpServletRequest request = Request.unwrap(event.getSuppliedRequest());
        final HttpServletResponse response = org.eclipse.jetty.ee9.nested.Response.unwrap(event.getSuppliedResponse());

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} {} on {}", request.getDispatcherType(), request.getMethod(), target, channel);
        handle(target, baseRequest, request, response);
        if (LOG.isDebugEnabled())
            LOG.debug("handledAsync={} async={} committed={} on {}", channel.getRequest().isHandled(), request.isAsyncStarted(), response.isCommitted(), channel);
    }

    /**
     * Context.
     * <p>
     * A partial implementation of {@link jakarta.servlet.ServletContext}. A complete implementation is provided by the
     * derived {@link ContextHandler} implementations.
     * </p>
     */
    public class APIContext implements ServletContext
    {
        private final ScopedContext _coreContext;
        protected boolean _enabled = true; // whether or not the dynamic API is enabled for callers
        protected boolean _extendedListenerTypes = false;
        private int _effectiveMajorVersion = SERVLET_MAJOR_VERSION;
        private int _effectiveMinorVersion = SERVLET_MINOR_VERSION;

        protected APIContext()
        {
            _coreContext = _coreContextHandler.getContext();
        }

        org.eclipse.jetty.server.Context getCoreContext()
        {
            return _coreContext;
        }

        @Override
        public int getMajorVersion()
        {
            return SERVLET_MAJOR_VERSION;
        }

        @Override
        public int getMinorVersion()
        {
            return SERVLET_MINOR_VERSION;
        }

        @Override
        public String getServerInfo()
        {
            return getServer().getServerInfo();
        }

        @Override
        public int getEffectiveMajorVersion()
        {
            return _effectiveMajorVersion;
        }

        @Override
        public int getEffectiveMinorVersion()
        {
            return _effectiveMinorVersion;
        }

        public void setEffectiveMajorVersion(int v)
        {
            _effectiveMajorVersion = v;
        }

        public void setEffectiveMinorVersion(int v)
        {
            _effectiveMinorVersion = v;
        }

        public ContextHandler getContextHandler()
        {
            return ContextHandler.this;
        }

        @Override
        public ServletContext getContext(String path)
        {
            org.eclipse.jetty.server.handler.ContextHandler context = getContextHandler().getCoreContextHandler().getCrossContextHandler(path);

            if (context == null)
                return null;
            if (context == _coreContextHandler)
                return this;
            return new CrossContextServletContext(_coreContextHandler, context.getContext());
        }

        @Override
        public String getMimeType(String file)
        {
            return _coreContext.getMimeTypes().getMimeByExtension(file);
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String uriInContext)
        {
            // uriInContext is encoded, potentially with query.
            if (uriInContext == null)
                return null;

            if (!uriInContext.startsWith("/"))
                return null;

            try
            {
                String contextPath = getContextPath();
                // uriInContext is canonicalized by HttpURI.
                HttpURI.Mutable uri = HttpURI.build(uriInContext);
                String pathInfo = uri.getDecodedPath();
                if (StringUtil.isEmpty(pathInfo))
                    return null;

                if (!StringUtil.isEmpty(contextPath))
                {
                    uri.path(URIUtil.addPaths(contextPath, uri.getPath()));
                    pathInfo = uri.getDecodedPath().substring(contextPath.length());
                }
                return new Dispatcher(ContextHandler.this, uri, pathInfo);
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
            }
            return null;
        }

        @Override
        public String getRealPath(String path)
        {
            // This is an API call from the application which may pass non-canonical paths.
            // Thus, we canonicalize here, to avoid the enforcement of canonical paths in
            // ContextHandler.this.getResource(path).
            path = URIUtil.canonicalPath(path);
            if (path == null)
                return null;
            if (path.length() == 0)
                path = "/";
            else if (path.charAt(0) != '/')
                path = "/" + path;

            try
            {
                Resource resource = ContextHandler.this.getResource(path);
                if (resource != null)
                {
                    for (Resource r : resource)
                    {
                        // return first
                        if (Resources.exists(r))
                        {
                            Path resourcePath = r.getPath();
                            if (resourcePath != null)
                            {
                                String realPath = resourcePath.normalize().toString();
                                if (Files.isDirectory(resourcePath))
                                    realPath = realPath + "/";
                                return realPath;
                            }
                        }
                    }

                    // A Resource was returned, but did not exist
                    return null;
                }
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
            }

            return null;
        }

        @Override
        public URL getResource(String path) throws MalformedURLException
        {
            try
            {
                // This is an API call from the application which may pass non-canonical paths.
                // Thus, we canonicalize here, to avoid the enforcement of canonical paths in
                // ContextHandler.this.getResource(path).
                String canonicalPath = URIUtil.canonicalPath(path);
                if (canonicalPath == null)
                    return null;

                if (!canonicalPath.startsWith("/"))
                    throw new MalformedURLException(path);

                Resource resource = ContextHandler.this.getResource(canonicalPath);
                if (resource != null && resource.exists())
                    return resource.getURI().toURL();
            }
            catch (MalformedURLException e)
            {
                throw e;
            }
            catch (Throwable e)
            {
                // catch IOException, RuntimeException, and things like java.nio.fileInvalidPathException here.
                throw (MalformedURLException)new MalformedURLException(path).initCause(e);
            }
            return null;
        }

        @Override
        public InputStream getResourceAsStream(String path)
        {
            try
            {
                URL url = getResource(path);
                if (url == null)
                    return null;
                Resource r = ResourceFactory.of(ContextHandler.this).newResource(url);
                // Cannot serve directories as an InputStream
                if (r.isDirectory())
                    return null;
                return IOResources.asInputStream(r);
            }
            catch (Throwable e)
            {
                // catch IOException, RuntimeException, and things like java.nio.fileInvalidPathException here.
                LOG.trace("IGNORED", e);
                return null;
            }
        }

        @Override
        public Set<String> getResourcePaths(String path)
        {
            // This is an API call from the application which may pass non-canonical paths.
            // Thus, we canonicalize here, to avoid the enforcement of canonical paths in
            // ContextHandler.this.getResource(path).
            path = URIUtil.canonicalPath(path);
            if (path == null)
                return null;
            return ContextHandler.this.getResourcePaths(path);
        }

        @Override
        public void log(Exception exception, String msg)
        {
            _logger.warn(msg, exception);
        }

        @Override
        public void log(String msg)
        {
            _logger.info(msg);
        }

        @Override
        public void log(String message, Throwable throwable)
        {
            if (throwable == null)
                _logger.warn(message);
            else
                _logger.warn(message, throwable);
        }

        @Override
        public String getInitParameter(String name)
        {
            return ContextHandler.this.getInitParameter(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames()
        {
            return ContextHandler.this.getInitParameterNames();
        }

        @Override
        public Object getAttribute(String name)
        {
            return _coreContext.getAttribute(name);
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            return Collections.enumeration(_coreContext.getAttributeNameSet());
        }

        @Override
        public void setAttribute(String name, Object value)
        {
            Object oldValue = _coreContext.setAttribute(name, value);

            if (!_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_apiContext, name, oldValue == null ? value : oldValue);

                for (ServletContextAttributeListener listener : _servletContextAttributeListeners)
                {
                    if (oldValue == null)
                        listener.attributeAdded(event);
                    else if (value == null)
                        listener.attributeRemoved(event);
                    else
                        listener.attributeReplaced(event);
                }
            }
        }

        @Override
        public void removeAttribute(String name)
        {
            Object oldValue = _coreContext.removeAttribute(name);
            if (oldValue != null && !_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_apiContext, name, oldValue);
                for (ServletContextAttributeListener listener : _servletContextAttributeListeners)
                {
                    listener.attributeRemoved(event);
                }
            }
        }

        @Override
        public String getServletContextName()
        {
            String name = ContextHandler.this.getDisplayName();
            if (name == null)
                name = ContextHandler.this.getContextPath();
            return name;
        }

        @Override
        public String getContextPath()
        {
            return getRequestContextPath();
        }

        @Override
        public String toString()
        {
            return "ServletContext@" + ContextHandler.this.toString();
        }

        @Override
        public boolean setInitParameter(String name, String value)
        {
            if (ContextHandler.this.getInitParameter(name) != null)
                return false;
            ContextHandler.this.getInitParams().put(name, value);
            return true;
        }

        @Override
        public void addListener(String className)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            try
            {
                ClassLoader classLoader = _coreContext.getClassLoader();
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<? extends EventListener> clazz = classLoader == null ? Loader.loadClass(className) : (Class)classLoader.loadClass(className);
                addListener(clazz);
            }
            catch (ClassNotFoundException e)
            {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public <T extends EventListener> void addListener(T t)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            checkListener(t.getClass());

            ContextHandler.this.addEventListener(t);
            ContextHandler.this.addProgrammaticListener(t);
        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            try
            {
                EventListener e = createListener(listenerClass);
                addListener(e);
            }
            catch (ServletException e)
            {
                throw new IllegalArgumentException(e);
            }
        }

        public void checkListener(Class<? extends EventListener> listener) throws IllegalStateException
        {
            boolean ok = false;
            int startIndex = (isExtendedListenerTypes() ? EXTENDED_LISTENER_TYPE_INDEX : DEFAULT_LISTENER_TYPE_INDEX);
            for (int i = startIndex; i < SERVLET_LISTENER_TYPES.length; i++)
            {
                if (SERVLET_LISTENER_TYPES[i].isAssignableFrom(listener))
                {
                    ok = true;
                    break;
                }
            }
            if (!ok)
                throw new IllegalArgumentException("Inappropriate listener class " + listener.getName());
        }

        public void setExtendedListenerTypes(boolean extended)
        {
            _extendedListenerTypes = extended;
        }

        public boolean isExtendedListenerTypes()
        {
            return _extendedListenerTypes;
        }

        @Override
        public RequestDispatcher getNamedDispatcher(String name)
        {
            return null;
        }

        @Override
        public Servlet getServlet(String name) throws ServletException
        {
            return null;
        }

        @Override
        public Enumeration<Servlet> getServlets()
        {
            return null;
        }

        @Override
        public Enumeration<String> getServletNames()
        {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, String className)
        {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet)
        {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass)
        {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile)
        {
            return null;
        }

        @Override
        public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException
        {
            return createInstance(clazz);
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName)
        {
            return null;
        }

        @Override
        public Map<String, ? extends ServletRegistration> getServletRegistrations()
        {
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, String className)
        {
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, Filter filter)
        {
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, Class<? extends Filter> filterClass)
        {
            return null;
        }

        public <T> T createInstance(Class<T> clazz) throws ServletException
        {
            try
            {
                T instance = clazz.getDeclaredConstructor().newInstance();
                return getCoreContext().decorate(instance);
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }

        @Override
        public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException
        {
            return createInstance(clazz);
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName)
        {
            return null;
        }

        @Override
        public Map<String, ? extends FilterRegistration> getFilterRegistrations()
        {
            return null;
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig()
        {
            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
        {

        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
        {
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
        {
            return null;
        }

        @Override
        public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException
        {
            return createInstance(clazz);
        }

        @Override
        public int getSessionTimeout()
        {
            return 0;
        }

        @Override
        public void setSessionTimeout(int sessionTimeout)
        {

        }

        @Override
        public String getRequestCharacterEncoding()
        {
            return getDefaultRequestCharacterEncoding();
        }

        @Override
        public void setRequestCharacterEncoding(String encoding)
        {
            setDefaultRequestCharacterEncoding(encoding);
        }

        @Override
        public String getResponseCharacterEncoding()
        {
            return getDefaultResponseCharacterEncoding();
        }

        @Override
        public void setResponseCharacterEncoding(String encoding)
        {
            setDefaultResponseCharacterEncoding(encoding);
        }

        @Override
        public ClassLoader getClassLoader()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            // no security manager just return the classloader
            if (!isUsingSecurityManager())
            {
                return _coreContext.getClassLoader();
            }
            else
            {
                // check to see if the classloader of the caller is the same as the context
                // classloader, or a parent of it, as required by the javadoc specification.
                ClassLoader classLoader = _coreContext.getClassLoader();
                ClassLoader callerLoader = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .getCallerClass()
                    .getClassLoader();
                while (callerLoader != null)
                {
                    if (callerLoader == classLoader)
                        return classLoader;
                    else
                        callerLoader = callerLoader.getParent();
                }
                SecurityUtils.checkPermission(new RuntimePermission("getClassLoader"));
                return classLoader;
            }
        }

        @Override
        public JspConfigDescriptor getJspConfigDescriptor()
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getJspConfigDescriptor()");
            return null;
        }

        public void setJspConfigDescriptor(JspConfigDescriptor d)
        {

        }

        @Override
        public void declareRoles(String... roleNames)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
        }

        public void setEnabled(boolean enabled)
        {
            _enabled = enabled;
        }

        public boolean isEnabled()
        {
            return _enabled;
        }

        @Override
        public String getVirtualServerName()
        {
            String[] hosts = getVirtualHosts();
            if (hosts != null && hosts.length > 0)
                return hosts[0];
            return null;
        }
    }

    /**
     * Listener for all threads entering context scope, including async IO callbacks
     */
    public interface ContextScopeListener extends EventListener
    {
        /**
         * @param context The context being entered
         * @param request A request that is applicable to the scope, or null
         * @param reason An object that indicates the reason the scope is being entered.
         */
        void enterScope(APIContext context, Request request, Object reason);

        /**
         * @param context The context being exited
         * @param request A request that is applicable to the scope, or null
         */
        void exitScope(APIContext context, Request request);
    }

    public static class CoreContextRequest extends ContextRequest
    {
        private final HttpChannel _httpChannel;
        private SessionManager _sessionManager;
        private ManagedSession _managedSession;
        private List<ManagedSession> _managedSessions;

        AbstractSessionManager.RequestedSession _requestedSession;

        protected CoreContextRequest(org.eclipse.jetty.server.Request wrapped,
                                     ScopedContext context,
                                     HttpChannel httpChannel)
        {
            super(context, wrapped);
            _httpChannel = httpChannel;
        }

        public String changeSessionId()
        {
            if (_managedSession == null)
                throw new IllegalStateException("No session");

            if (!_managedSession.isValid())
                return _managedSession.getId();

            HttpSession httpSession = _managedSession.getApi();
            if (httpSession == null)
                throw new IllegalStateException("No session");

            ManagedSession session = _managedSession;
            session.renewId(this, _httpChannel.getCoreResponse());

            return httpSession.getId();
        }

        public HttpChannel getHttpChannel()
        {
            return _httpChannel;
        }

        public ManagedSession getManagedSession()
        {
            return _managedSession;
        }

        /**
         * Retrieve an existing session, if one exists, for a given SessionManager. A
         * session belongs to a single SessionManager, and a context can only have a single
         * SessionManager. Thus, calling this method is equivalent to asking
         * "Does a ManagedSession already exist for the given context?".
         *
         * @param manager the SessionManager that should be associated with a ManagedSession
         * @return the ManagedSession that already exists in the given context and is managed
         * by the given SessionManager.
         */
        public ManagedSession getManagedSession(SessionManager manager)
        {
            if (_managedSessions == null)
                return null;

            for (ManagedSession s : _managedSessions)
            {
                if (manager == s.getSessionManager())
                {
                   if (s.isValid())
                       return s;
                }
            }
            return null;
        }

        public void setManagedSession(ManagedSession managedSession)
        {
            _managedSession = managedSession;
            addManagedSession(managedSession);
        }

        /**
         * Add a session to the list of sessions maintained by this request.
         * A session will be added whenever a request visits a new context
         * that already has a session associated with it, or one is created
         * during the dispatch.
         *
         * @param managedSession the session to add
         */
        private void addManagedSession(ManagedSession managedSession)
        {
            if (managedSession == null)
                return;
            if (_managedSessions == null)
                _managedSessions = new ArrayList<>();
            if (!_managedSessions.contains(managedSession))
                _managedSessions.add(managedSession);
        }

        public SessionManager getSessionManager()
        {
            return _sessionManager;
        }

        /**
         * Remember the session that was extracted from the id in the request
         *
         * @param requestedSession info about the session matching the id in the request
         */
        public void setRequestedSession(AbstractSessionManager.RequestedSession requestedSession)
        {
            _requestedSession = requestedSession;
        }

        /**
         * Release each of the sessions as the request is now complete
         */
        public void completeSessions()
        {
            if (_managedSessions != null)
            {
                for (ManagedSession s : _managedSessions)
                {

                    if (s.getSessionManager() == null)
                        continue; //TODO log it
                    s.getSessionManager().getContext().run(() -> completeSession(s), this);
                }
            }
        }

        /**
         * Ensure that each session is committed - ie written out to storage if necessary -
         * because the response is about to be returned to the client.
         */
        public void commitSessions()
        {
            if (_managedSessions != null)
            {
                for (ManagedSession s : _managedSessions)
                {
                    if (s.getSessionManager() == null)
                        continue; //TODO log it
                    s.getSessionManager().getContext().run(() -> commitSession(s), this);
                }
            }
        }

        private void commitSession(ManagedSession session)
        {
            if (session == null)
                return;
            SessionManager manager = session.getSessionManager();
            if (manager == null)
                return;
            manager.commit(session);
        }

        private void completeSession(ManagedSession session)
        {
            if (session == null)
                return;
            SessionManager manager = session.getSessionManager();
            if (manager == null)
                return;
            manager.complete(session);
        }

        public AbstractSessionManager.RequestedSession getRequestedSession()
        {
            return _requestedSession;
        }

        public void setSessionManager(SessionManager sessionManager)
        {
            _sessionManager = sessionManager;
        }

        @Override
        public Session getSession(boolean create)
        {
            if (_managedSession != null)
            {
                if (_sessionManager != null && !_managedSession.isValid())
                    _managedSession = null;
                else
                    return _managedSession;
            }

            if (!create)
                return null;

            if (_httpChannel.getResponse().isCommitted())
                throw new IllegalStateException("Response is committed");

            if (_sessionManager == null)
                throw new IllegalStateException("No SessionManager");

            _sessionManager.newSession(this, _requestedSession == null ? null : _requestedSession.sessionId(), this::setManagedSession);

            if (_managedSession == null)
                throw new IllegalStateException("Create session failed");

            HttpCookie cookie = _sessionManager.getSessionCookie(_managedSession, isSecure());
            if (cookie != null)
                _httpChannel.getResponse().replaceCookie(cookie);

            return _managedSession;
        }
    }

    public class CoreContextHandler extends org.eclipse.jetty.server.handler.ContextHandler implements org.eclipse.jetty.server.Request.Handler
    {
        CoreContextHandler()
        {
            super.setHandler(new CoreToNestedHandler());
            installBean(ContextHandler.this, true);
        }

        @Override
        public void makeTempDirectory() throws Exception
        {
            super.makeTempDirectory();
        }

        @Override
        public String getCanonicalNameForTmpDir()
        {
            return super.getCanonicalNameForTmpDir();
        }

        @Override
        public Resource getResourceForTempDirName()
        {
           return ContextHandler.this.getNestedResourceForTempDirName();
        }

        private Resource getSuperResourceForTempDirName()
        {
           return super.getResourceForTempDirName();
        }

        public void setTempDirectory(File dir)
        {
            super.setTempDirectory(dir);
            setAttribute(ServletContext.TEMPDIR, super.getTempDirectory());
        }

        @Override
        public void setContextPath(String contextPath)
        {
            super.setContextPath(contextPath);
            _contextPathEncoded = URIUtil.encodePath(contextPath);

            // update context mappings
            if (getServer() != null && getServer().isRunning())
                getServer().getDescendants(ContextHandlerCollection.class).forEach(ContextHandlerCollection::mapContexts);
        }

        @Override
        protected void doStart() throws Exception
        {
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            if (getClassLoader() != null)
                Thread.currentThread().setContextClassLoader(getClassLoader());
            try
            {
                super.doStart();
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(old);
            }
        }

        /*
         * Expose configureTempDirectory so it can be triggered early by WebInfConfiguration#preConfigure
         */
        @Override
        public void createTempDirectory()
        {
            super.createTempDirectory();
        }

        @Override
        protected void doStop() throws Exception
        {
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            if (getClassLoader() != null)
                Thread.currentThread().setContextClassLoader(getClassLoader());
            try
            {
                super.doStop();
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(old);
            }
        }

        @Override
        public void insertHandler(Singleton handler)
        {
            // We cannot call super.insertHandler here, because it uses this.setHandler
            // which gives a warning.  This is the same code, but uses super.setHandler
            Singleton tail = handler.getTail();
            if (tail.getHandler() != null)
                throw new IllegalArgumentException("bad tail of inserted wrapper chain");

            tail.setHandler(getHandler());
            super.setHandler(handler);
        }

        @Override
        public void setHandler(Handler handler)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setServer(Server server)
        {
            super.setServer(server);
            ContextHandler.this.setServer(server);
        }

        @Override
        protected ScopedContext newContext()
        {
            return new CoreContext();
        }

        @Override
        protected ContextRequest wrapRequest(org.eclipse.jetty.server.Request request, Response response)
        {
            HttpChannel httpChannel = (HttpChannel)request.getComponents().getCache().getAttribute(HttpChannel.class.getName());
            if (httpChannel == null)
            {
                httpChannel = new HttpChannel(ContextHandler.this, request.getConnectionMetaData());
                request.getComponents().getCache().setAttribute(HttpChannel.class.getName(), httpChannel);
            }
            else if (httpChannel.getContextHandler() == ContextHandler.this && !request.getContext().isCrossContextDispatch(request))
            {
                httpChannel.recycle();
            }
            else
            {
                // Don't use cached channel for secondary context
                httpChannel = new HttpChannel(ContextHandler.this, request.getConnectionMetaData());
            }

            CoreContextRequest coreContextRequest = new CoreContextRequest(request, this.getContext(), httpChannel);
            httpChannel.onRequest(coreContextRequest);
            HttpChannel channel = httpChannel;
            org.eclipse.jetty.server.Request.addCompletionListener(coreContextRequest, x ->
            {
                // WebSocket needs a reference to the HttpServletRequest,
                // so do not recycle the HttpChannel if it's a WebSocket
                // request, no matter if the response is successful or not.
                if (!request.getHeaders().contains(HttpHeader.SEC_WEBSOCKET_VERSION))
                    channel.recycle();
            });
            return coreContextRequest;
        }

        @Override
        protected void notifyEnterScope(org.eclipse.jetty.server.Request coreRequest)
        {
            __context.set(_apiContext);
            super.notifyEnterScope(coreRequest);
            Request request = (coreRequest instanceof CoreContextRequest coreContextRequest)
                    ? coreContextRequest.getHttpChannel().getRequest()
                    : null;
            ContextHandler.this.enterScope(request, "Entered core context");
        }

        @Override
        protected void notifyExitScope(org.eclipse.jetty.server.Request coreRequest)
        {
            try
            {
                Request request = (coreRequest instanceof CoreContextRequest coreContextRequest)
                        ? coreContextRequest.getHttpChannel().getRequest()
                        : null;
                ContextHandler.this.exitScope(request);
                super.notifyExitScope(coreRequest);
            }
            finally
            {
                __context.set(null);
            }
        }

        public ContextHandler getContextHandler()
        {
            return ContextHandler.this;
        }

        class CoreContext extends ScopedContext
        {
            public APIContext getAPIContext()
            {
                return _apiContext;
            }

            @Override
            public Object getAttribute(String name)
            {
                return switch (name)
                {
                    case FormFields.MAX_FIELDS_ATTRIBUTE -> getMaxFormKeys();
                    case FormFields.MAX_LENGTH_ATTRIBUTE -> getMaxFormContentSize();
                    default -> super.getAttribute(name);
                };
            }

            @Override
            public Object setAttribute(String name, Object attribute)
            {
                return switch (name)
                {
                    case FormFields.MAX_FIELDS_ATTRIBUTE ->
                    {
                        int oldValue = getMaxFormKeys();
                        if (attribute == null)
                            setMaxFormKeys(DEFAULT_MAX_FORM_KEYS);
                        else
                            setMaxFormKeys(Integer.parseInt(attribute.toString()));
                        yield oldValue;
                    }
                    case FormFields.MAX_LENGTH_ATTRIBUTE ->
                    {
                        int oldValue = getMaxFormContentSize();
                        if (attribute == null)
                            setMaxFormContentSize(DEFAULT_MAX_FORM_CONTENT_SIZE);
                        else
                            setMaxFormContentSize(Integer.parseInt(attribute.toString()));
                        yield oldValue;
                    }
                    default -> super.setAttribute(name, attribute);
                };
            }
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> names = new HashSet<>(super.getAttributeNameSet());
            names.add(FormFields.MAX_FIELDS_ATTRIBUTE);
            names.add(FormFields.MAX_LENGTH_ATTRIBUTE);
            return Collections.unmodifiableSet(names);
        }

        private class CoreToNestedHandler extends Abstract
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request coreRequest, Response response, Callback callback)
            {
                HttpChannel httpChannel = org.eclipse.jetty.server.Request.get(coreRequest, CoreContextRequest.class, CoreContextRequest::getHttpChannel);
                Objects.requireNonNull(httpChannel).onProcess(response, callback);
                httpChannel.handle();
                return true;
            }
        }
    }

    public Resource getNestedResourceForTempDirName()
    {
        return getCoreContextHandler().getSuperResourceForTempDirName();
    }
}
