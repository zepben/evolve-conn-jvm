# Evolve SDK connection library
## [0.8.0] - UNRELEASED
### Breaking Changes
* Use super pom version 0.34.x, which uses Vert.x version 4.4.6 (major version change 3 &rarr; 4).
* Removed unused module `com.zepben.auth.server.vertx`
* `AuthConfigRoute` now expects the key `issuer` instead of `issuerDomain` for the OAuth provider's hostname.

### New Features
* None.

### Enhancements
* None.

### Fixes
* After the move to vertx 4, we dropped using our own User module that ran Jwt token verifications. This fixes the 
issue by storing the token in the Vertx User object and verifying it separately.

### Notes
* None.

## [0.7.0] - 2023-11-10
##### Breaking Changes
* None.

##### New Features
* Support Azure Entra ID as an OAuth2 Auth provider
* Support Azure Identity token fetcher
* Support passing in your own authorisation callback to AuthInterceptor.

##### Enhancements
* `createTokenFetcher` now matches the authentication method (e.g. `OAUTH`) in the auth config JSON in a
  case-insensitive manner.

##### Fixes
* Refresh token is now used in token refresh requests.
* `createTokenFetcher` now defaults the token path to `\oauth\token` if it is unspecified in the auth config JSON.

##### Notes
* None.

## [0.5.0]
##### Breaking Changes
* There are 3 ways of specifying trusted TLS/SSL certificates when constructing a `ZepbenTokenFetcher`:
  * Directly specify the `HttpClient` via the `client` parameter
  * Specify whether to verify the certificate via the `verifyCertificate` parameter
  * Specify a CA file via the `caFilename` parameter
  
  This change prevents users of the library from trying to use more than one of these methods. A similar change was
  performed on the `createTokenFetcher` function; the following parameter groups are mutually exclusive:
  * `confClient` and `authClient`
  * `verifyCertificates`
  * `confCAFilename` and `authCAFilename`

##### New Features
* None.

##### Enhancements
* None.

##### Fixes
* Refresh token is now used in token refresh requests.

##### Notes
* None.
