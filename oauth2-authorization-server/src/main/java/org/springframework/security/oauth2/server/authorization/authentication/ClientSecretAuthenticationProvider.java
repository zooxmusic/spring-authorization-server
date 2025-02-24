/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization.authentication;

import java.time.Instant;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;

/**
 * An {@link AuthenticationProvider} implementation used for OAuth 2.0 Client Authentication,
 * which authenticates the {@link OAuth2ParameterNames#CLIENT_SECRET client_secret} parameter.
 *
 * @author Patryk Kostrzewa
 * @author Joe Grandja
 * @since 0.2.3
 * @see AuthenticationProvider
 * @see OAuth2ClientAuthenticationToken
 * @see RegisteredClientRepository
 * @see OAuth2AuthorizationService
 * @see PasswordEncoder
 */
public final class ClientSecretAuthenticationProvider implements AuthenticationProvider {
	private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-3.2.1";
	private final RegisteredClientRepository registeredClientRepository;
	private final CodeVerifierAuthenticator codeVerifierAuthenticator;
	private PasswordEncoder passwordEncoder;

	/**
	 * Constructs a {@code ClientSecretAuthenticationProvider} using the provided parameters.
	 *
	 * @param registeredClientRepository the repository of registered clients
	 * @param authorizationService the authorization service
	 */
	public ClientSecretAuthenticationProvider(RegisteredClientRepository registeredClientRepository,
			OAuth2AuthorizationService authorizationService) {
		Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
		Assert.notNull(authorizationService, "authorizationService cannot be null");
		this.registeredClientRepository = registeredClientRepository;
		this.codeVerifierAuthenticator = new CodeVerifierAuthenticator(authorizationService);
		this.passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	/**
	 * Sets the {@link PasswordEncoder} used to validate
	 * the {@link RegisteredClient#getClientSecret() client secret}.
	 * If not set, the client secret will be compared using
	 * {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()}.
	 *
	 * @param passwordEncoder the {@link PasswordEncoder} used to validate the client secret
	 */
	public void setPasswordEncoder(PasswordEncoder passwordEncoder) {
		Assert.notNull(passwordEncoder, "passwordEncoder cannot be null");
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		OAuth2ClientAuthenticationToken clientAuthentication =
				(OAuth2ClientAuthenticationToken) authentication;

		if (!ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(clientAuthentication.getClientAuthenticationMethod()) &&
				!ClientAuthenticationMethod.CLIENT_SECRET_POST.equals(clientAuthentication.getClientAuthenticationMethod())) {
			return null;
		}

		String clientId = clientAuthentication.getPrincipal().toString();
		RegisteredClient registeredClient = this.registeredClientRepository.findByClientId(clientId);
		if (registeredClient == null) {
			throwInvalidClient(OAuth2ParameterNames.CLIENT_ID);
		}

		if (!registeredClient.getClientAuthenticationMethods().contains(
				clientAuthentication.getClientAuthenticationMethod())) {
			throwInvalidClient("authentication_method");
		}

		if (clientAuthentication.getCredentials() == null) {
			throwInvalidClient("credentials");
		}

		String clientSecret = clientAuthentication.getCredentials().toString();
		if (!this.passwordEncoder.matches(clientSecret, registeredClient.getClientSecret())) {
			throwInvalidClient(OAuth2ParameterNames.CLIENT_SECRET);
		}

		if (registeredClient.getClientSecretExpiresAt() != null &&
				Instant.now().isAfter(registeredClient.getClientSecretExpiresAt())) {
			throwInvalidClient("client_secret_expires_at");
		}

		// Validate the "code_verifier" parameter for the confidential client, if available
		this.codeVerifierAuthenticator.authenticateIfAvailable(clientAuthentication, registeredClient);

		return new OAuth2ClientAuthenticationToken(registeredClient,
				clientAuthentication.getClientAuthenticationMethod(), clientAuthentication.getCredentials());
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
	}

	private static void throwInvalidClient(String parameterName) {
		OAuth2Error error = new OAuth2Error(
				OAuth2ErrorCodes.INVALID_CLIENT,
				"Client authentication failed: " + parameterName,
				ERROR_URI
		);
		throw new OAuth2AuthenticationException(error);
	}

}
