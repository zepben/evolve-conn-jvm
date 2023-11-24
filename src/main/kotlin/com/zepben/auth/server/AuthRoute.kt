/*
 * Copyright 2023 Zeppelin Bend Pty Ltd
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */


package com.zepben.auth.server

import com.auth0.jwk.UrlJwkProvider
import com.zepben.auth.server.vertx.JWTAuthProvider
import com.zepben.vertxutils.routing.Route
import com.zepben.vertxutils.routing.RouteVersion
import com.zepben.vertxutils.routing.VersionableRoute
import io.vertx.core.http.HttpMethod

/**
 * A route for authenticating users based on Auth0 JWTs.
 */
class AuthRoute {
    companion object {

        /**
         * Creates a route that has a handler implementing [io.vertx.ext.web.handler.AuthHandler] that supports
         * Auth0 JWTs using [JWTAuthProvider] and [JWTAuthenticator].
         *
         * @param availableRoute The [AvailableRoute] for API version management.
         * @param path The path the [Route] should be built on
         * @param audience The audience required for JWT authentication
         * @param issuer The issuer domain required for JWT authentication
         * @param requiredClaims The claims required for the JWT for authorisation.
         */
        @JvmOverloads
        @JvmStatic
        fun routeFactory(
            path: String,
            audience: String,
            jwkProvider: UrlJwkProvider,
            issuer: String,
            requiredClaims: Iterable<String> = emptySet(),
            isRegexPath: Boolean = false
        ): (AvailableRoute) -> Route =
            { availableRoute ->
                when (availableRoute) {
                    AvailableRoute.AUTH ->
                        Route.builder()
                            .methods(*HttpMethod.values().toTypedArray())
                            .path(path)
                            .hasRegexPath(isRegexPath)
                            .addHandler(
                                Auth0AuthHandler(
                                    JWTAuthProvider(JWTAuthenticator(audience, issuer, jwkProvider)),
                                    mutableSetOf<String>().apply { addAll(requiredClaims) }
                                )
                            )
                            .build()
                }
            }
    }

    enum class AvailableRoute(private val rv: RouteVersion) : VersionableRoute {
        AUTH(RouteVersion.since(0));

        override fun routeVersion(): RouteVersion {
            return rv
        }
    }
}
