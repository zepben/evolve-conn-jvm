/*
 * Copyright 2023 Zeppelin Bend Pty Ltd
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */


package com.zepben.auth.server

import com.zepben.auth.server.vertx.JWTAuthProvider
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.User
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.AuthenticationHandler
import io.vertx.ext.web.handler.HttpException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


class Auth0AuthHandler(
    val authProvider: JWTAuthProvider,
    requiredClaims: Set<String>,
    private val skip: String? = null
) :
    AuthenticationHandler {

    private val authorities = mutableSetOf<String>()

    init {
        addAuthorities(requiredClaims)
    }

    private fun addAuthority(authority: String): Auth0AuthHandler {
        authorities.add(authority)
        return this
    }

    private fun addAuthorities(authorities: Set<String>): Auth0AuthHandler {
        this.authorities.addAll(authorities)
        return this
    }

    private fun authorize(user: User?, handler: Handler<AsyncResult<Void?>>) {
        val requiredCount = authorities.size
        if (requiredCount > 0) {
            if (user == null) {
                handler.handle(Future.failedFuture(HttpException(403, "No user was found, you must authenticate first")))
                return
            }
            val count = AtomicInteger()
            val sentFailure = AtomicBoolean()
            val authHandler =
                Handler { res: AsyncResult<Boolean> ->
                    if (res.succeeded()) {
                        if (res.result()) {
                            if (count.incrementAndGet() == requiredCount) {
                                // Has all required authorities
                                handler.handle(Future.succeededFuture())
                            }
                        } else {
                            if (sentFailure.compareAndSet(false, true)) {
                                handler.handle(Future.failedFuture(HttpException(403, "Could not authorise all requested permissions. This is likely a bug.")))
                            }
                        }
                    } else {
                        handler.handle(Future.failedFuture(res.cause()))
                    }
                }
            for (authority in authorities) {
                if (!sentFailure.get()) {
                    user.isAuthorized(authority, authHandler)
                }
            }
        } else {
            // No auth required
            handler.handle(Future.succeededFuture())
        }
    }

    override fun handle(ctx: RoutingContext) {
        if (handlePreflight(ctx)) {
            return
        }
        val user = ctx.user()
        if (user != null) {
            // proceed to AuthZ
            authorizeUser(ctx, user)
            return
        }
        // parse the request in order to extract the credentials object
        parseCredentials(ctx) { res: AsyncResult<JsonObject> ->
            if (res.failed()) {
                processException(ctx, res.cause())
                return@parseCredentials
            }
            // check if the user has been set
            val updatedUser = ctx.user()
            if (updatedUser != null) {
                val session = ctx.session()
                session?.regenerateId()
                // proceed to AuthZ
                authorizeUser(ctx, updatedUser)
                return@parseCredentials
            }

            // proceed to authN
            authProvider.authenticate(
                res.result()
            ) { authN: AsyncResult<User> ->
                if (authN.succeeded()) {
                    val authenticated = authN.result()
                    ctx.setUser(authenticated)
                    val session = ctx.session()
                    session?.regenerateId()
                    // proceed to AuthZ
                    authorizeUser(ctx, authenticated)
                } else {
                    if (authN.cause() is HttpException) {
                        processException(ctx, authN.cause())
                    } else {
                        processException(ctx, HttpException(401, authN.cause()))
                    }
                }
            }
        }
    }

    private fun processException(ctx: RoutingContext, exception: Throwable?) {
        if (exception != null) {
            if (exception is HttpException) {
                val statusCode = exception.statusCode
                val payload = exception.payload
                when (statusCode) {
                    302 -> {
                        ctx.response()
                            .putHeader(HttpHeaders.LOCATION, payload)
                            .setStatusCode(302)
                            .end("Redirecting to $payload.")
                        return
                    }
                    else -> {
                        ctx.response()
                            .setStatusCode(exception.statusCode)
                            .setStatusMessage(exception.message)
                        payload?.let { ctx.response().end(payload) }
                        return
                    }
                }
            }
        }

        // fallback 500
        ctx.fail(exception)
    }

    private fun authorizeUser(ctx: RoutingContext, user: User) {
        authorize(user) { authZ ->
            if (authZ.failed()) {
                processException(ctx, authZ.cause())
                return@authorize
            }
            // success, allowed to continue
            ctx.next()
        }
    }

    private fun handlePreflight(ctx: RoutingContext): Boolean {
        val request = ctx.request()
        // See: https://www.w3.org/TR/cors/#cross-origin-request-with-preflight-0
        // Preflight requests should not be subject to security due to the reason UAs will remove the Authorization header
        if (request.method() == HttpMethod.OPTIONS) {
            // check if there is a access control request header
            val accessControlRequestHeader =
                ctx.request().getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS)
            if (accessControlRequestHeader != null) {
                // lookup for the Authorization header
                for (ctrlReq in accessControlRequestHeader.split(",".toRegex()).toTypedArray()) {
                    if (ctrlReq.equals("Authorization", ignoreCase = true)) {
                        // this request has auth in access control, so we can allow preflighs without authentication
                        ctx.next()
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun parseAuthorization(
        ctx: RoutingContext,
        handler: Handler<AsyncResult<String?>>
    ) {
        val request = ctx.request()
        val authorization = request.headers()[HttpHeaders.AUTHORIZATION] ?: run {
            handler.handle(
                Future.failedFuture(
                    HttpException(401, "Missing Authorization header")
                )
            ); return
        }

        try {
            val idx = authorization.indexOf(' ')
            if (idx <= 0) {
                handler.handle(Future.failedFuture(HttpException(400, "Badly formed Authorization header")))
                return
            }
            if (authorization.substring(0, idx) != "Bearer") {
                handler.handle(Future.failedFuture(HttpException(401, "Missing Bearer token from Authorization header")))
                return
            }
            handler.handle(Future.succeededFuture(authorization.substring(idx + 1)))
        } catch (e: RuntimeException) {
            handler.handle(Future.failedFuture(e))
        }
    }

    private fun parseCredentials(context: RoutingContext?, handler: Handler<AsyncResult<JsonObject>>?) {

        if (skip != null && context!!.normalisedPath().startsWith(skip)) {
            context.next()
            return
        }

        parseAuthorization(
            context!!,
            Handler { parseAuthorization: AsyncResult<String?> ->
                if (parseAuthorization.failed()) {
                    handler!!.handle(Future.failedFuture(parseAuthorization.cause()))
                    return@Handler
                }
                handler!!.handle(
                    Future.succeededFuture(
                        JsonObject().put("jwt", parseAuthorization.result())
                    )
                )
            }
        )
//        context.response().end() TODO: this must not occur on some endpoints. needs to occur if auth fails. maybe we are not
        // failing fast if authN/Z fails? need to make sure permissions are in the web client scope too - token is missing them.
    }

}
