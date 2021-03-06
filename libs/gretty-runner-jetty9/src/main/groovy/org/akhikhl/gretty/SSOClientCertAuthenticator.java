/*
 * Gretty
 *
 * Copyright (C) 2013-2014 Andrey Hihlovskiy and contributors.
 *
 * See the file "LICENSE" for copying and usage permission.
 * See the file "CONTRIBUTORS" for complete list of contributors.
 */
package org.akhikhl.gretty;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.authentication.ClientCertAuthenticator;
import org.eclipse.jetty.security.authentication.DeferredAuthentication;
import static org.eclipse.jetty.security.authentication.FormAuthenticator.__J_METHOD;
import static org.eclipse.jetty.security.authentication.FormAuthenticator.__J_POST;
import static org.eclipse.jetty.security.authentication.FormAuthenticator.__J_URI;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.MultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author akhikhl
 */
public class SSOClientCertAuthenticator extends ClientCertAuthenticator {

    private static final Logger LOG = LoggerFactory.getLogger(SSOClientCertAuthenticator.class);

    // "login" is copied without changes from FormAuthenticator
    @Override
    public UserIdentity login(String username, Object password, ServletRequest request)
    {

        UserIdentity user = super.login(username,password,request);
        if (user!=null)
        {
            HttpSession session = ((HttpServletRequest)request).getSession(true);
            Authentication cached=new SessionAuthentication(getAuthMethod(),user,password);
            session.setAttribute(SessionAuthentication.__J_AUTHENTICATED, cached);
        }
        return user;
    }

    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException
    {
        HttpServletRequest request = (HttpServletRequest)req;

        if (!mandatory)
            return new DeferredAuthentication(this);

        // ++ copied from FormAuthenticator

        HttpSession session = request.getSession(true);

        // Look for cached authentication
        Authentication authentication = (Authentication) session.getAttribute(SessionAuthentication.__J_AUTHENTICATED);
        if (authentication != null)
        {
            // Has authentication been revoked?
            if (authentication instanceof Authentication.User &&
                _loginService!=null &&
                !_loginService.validate(((Authentication.User)authentication).getUserIdentity()))
            {
                LOG.debug("auth revoked {}",authentication);
                session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
            }
            else
            {
                synchronized (session)
                {
                    String j_uri=(String)session.getAttribute(__J_URI);
                    if (j_uri!=null)
                    {
                        //check if the request is for the same url as the original and restore
                        //params if it was a post
                        LOG.debug("auth retry {}->{}",authentication,j_uri);
                        StringBuffer buf = request.getRequestURL();
                        if (request.getQueryString() != null)
                            buf.append("?").append(request.getQueryString());

                        if (j_uri.equals(buf.toString()))
                        {
                            MultiMap<String> j_post = (MultiMap<String>)session.getAttribute(__J_POST);
                            if (j_post!=null)
                            {
                                LOG.debug("auth rePOST {}->{}",authentication,j_uri);
                                Request base_request = HttpChannel.getCurrentHttpChannel().getRequest();
                                base_request.setContentParameters(j_post);
                            }
                            session.removeAttribute(__J_URI);
                            session.removeAttribute(__J_METHOD);
                            session.removeAttribute(__J_POST);
                        }
                    }
                }
                LOG.debug("auth {}",authentication);
                return authentication;
            }
        }
        // -- copied from FormAuthenticator

        return super.validateRequest(req, res, mandatory);
    }
}
