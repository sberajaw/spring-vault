/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.Base64Utils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.vault.VaultException;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.support.RawTransitKey;
import org.springframework.vault.support.TransitKeyType;
import org.springframework.vault.support.VaultDecryptionResult;
import org.springframework.vault.support.VaultEncryptionResult;
import org.springframework.vault.support.VaultResponse;
import org.springframework.vault.support.VaultResponseSupport;
import org.springframework.vault.support.VaultTransitContext;
import org.springframework.vault.support.VaultTransitKey;
import org.springframework.vault.support.VaultTransitKeyConfiguration;
import org.springframework.vault.support.VaultTransitKeyCreationRequest;

/**
 * Default implementation of {@link VaultTransitOperations}.
 *
 * @author Mark Paluch
 * @author Sven Schürmann
 * @author Praveendra Singh
 */
public class VaultTransitTemplate implements VaultTransitOperations {

	private final VaultOperations vaultOperations;

	private final String path;

	public VaultTransitTemplate(VaultOperations vaultOperations, String path) {

		Assert.notNull(vaultOperations, "VaultOperations must not be null");
		Assert.hasText(path, "Path must not be empty");

		this.vaultOperations = vaultOperations;
		this.path = path;
	}

	@Override
	public void createKey(String keyName) {

		Assert.hasText(keyName, "KeyName must not be empty");

		vaultOperations.write(String.format("%s/keys/%s", path, keyName), null);
	}

	@Override
	public void createKey(String keyName, VaultTransitKeyCreationRequest createKeyRequest) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.notNull(createKeyRequest,
				"VaultTransitKeyCreationRequest must not be empty");

		vaultOperations.write(String.format("%s/keys/%s", path, keyName),
				createKeyRequest);
	}

	@Override
	public List<String> getKeys() {

		VaultResponse response = vaultOperations.read(String.format("%s/keys?list=true",
				path));

		return response == null ? Collections.emptyList() : (List) response
				.getRequiredData().get("keys");
	}

	@Override
	public void configureKey(String keyName, VaultTransitKeyConfiguration keyConfiguration) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.notNull(keyConfiguration, "VaultKeyConfiguration must not be empty");

		vaultOperations.write(String.format("%s/keys/%s/config", path, keyName),
				keyConfiguration);
	}

	@Override
	@Nullable
	public RawTransitKey exportKey(String keyName, TransitKeyType type) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.notNull(type, "Key type must not be null");

		VaultResponseSupport<RawTransitKeyImpl> result = vaultOperations.read(
				String.format("%s/export/%s/%s", path, type.getValue(), keyName),
				RawTransitKeyImpl.class);

		return result != null ? result.getRequiredData() : null;
	}

	@Override
	@Nullable
	public VaultTransitKey getKey(String keyName) {

		Assert.hasText(keyName, "KeyName must not be empty");

		VaultResponseSupport<VaultTransitKeyImpl> result = vaultOperations.read(
				String.format("%s/keys/%s", path, keyName), VaultTransitKeyImpl.class);

		if (result != null) {
			return result.getRequiredData();
		}

		return null;
	}

	@Override
	public void deleteKey(String keyName) {

		Assert.hasText(keyName, "KeyName must not be empty");

		vaultOperations.delete(String.format("%s/keys/%s", path, keyName));
	}

	@Override
	public void rotate(String keyName) {

		Assert.hasText(keyName, "KeyName must not be empty");

		vaultOperations.write(String.format("%s/keys/%s/rotate", path, keyName), null);
	}

	@Override
	public String encrypt(String keyName, String plaintext) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.notNull(plaintext, "Plaintext must not be null");

		Map<String, String> request = new LinkedHashMap<>();

		request.put("plaintext", Base64Utils.encodeToString(plaintext.getBytes()));

		return (String) vaultOperations
				.write(String.format("%s/encrypt/%s", path, keyName), request)
				.getRequiredData().get("ciphertext");
	}

	@Override
	public Ciphertext encrypt(String keyName, Plaintext plaintext) {

		Assert.notNull(plaintext, "Plaintext must not be null");

		String ciphertext = encrypt(keyName, plaintext.getPlaintext(),
				plaintext.getContext());

		return toCiphertext(ciphertext, plaintext.getContext());
	}

	@Override
	public String encrypt(String keyName, byte[] plaintext,
			VaultTransitContext transitContext) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.notNull(plaintext, "Plaintext must not be null");
		Assert.notNull(transitContext, "VaultTransitContext must not be null");

		Map<String, String> request = new LinkedHashMap<>();

		request.put("plaintext", Base64Utils.encodeToString(plaintext));

		applyTransitOptions(transitContext, request);

		return (String) vaultOperations
				.write(String.format("%s/encrypt/%s", path, keyName), request)
				.getRequiredData().get("ciphertext");
	}

	@Override
	public List<VaultEncryptionResult> encrypt(String keyName,
			List<Plaintext> batchRequest) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.notEmpty(batchRequest,
				"BatchRequest must not be null and must have at least one entry");

		List<Map<String, String>> batch = new ArrayList<Map<String, String>>(
				batchRequest.size());

		for (Plaintext request : batchRequest) {

			Map<String, String> vaultRequest = new LinkedHashMap<String, String>(2);

			vaultRequest.put("plaintext",
					Base64Utils.encodeToString(request.getPlaintext()));

			if (request.getContext() != null) {
				applyTransitOptions(request.getContext(), vaultRequest);
			}

			batch.add(vaultRequest);
		}

		VaultResponse vaultResponse = vaultOperations.write(
				String.format("%s/encrypt/%s", path, keyName),
				Collections.singletonMap("batch_input", batch));

		return toEncryptionResults(vaultResponse, batchRequest);
	}

	@Override
	public String decrypt(String keyName, String ciphertext) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.hasText(ciphertext, "Cipher text must not be empty");

		Map<String, String> request = new LinkedHashMap<>();

		request.put("ciphertext", ciphertext);

		String plaintext = (String) vaultOperations
				.write(String.format("%s/decrypt/%s", path, keyName), request)
				.getRequiredData().get("plaintext");

		return new String(Base64Utils.decodeFromString(plaintext));
	}

	@Override
	public Plaintext decrypt(String keyName, Ciphertext ciphertext) {

		Assert.hasText(keyName, "Ciphertext must not be null");

		byte[] plaintext = decrypt(keyName, ciphertext.getCiphertext(),
				ciphertext.getContext());

		return toPlaintext(plaintext, ciphertext.getContext());
	}

	@Override
	public byte[] decrypt(String keyName, String ciphertext,
			VaultTransitContext transitContext) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.hasText(ciphertext, "Cipher text must not be empty");
		Assert.notNull(transitContext, "VaultTransitContext must not be null");

		Map<String, String> request = new LinkedHashMap<>();

		request.put("ciphertext", ciphertext);

		applyTransitOptions(transitContext, request);

		String plaintext = (String) vaultOperations
				.write(String.format("%s/decrypt/%s", path, keyName), request)
				.getRequiredData().get("plaintext");

		return Base64Utils.decodeFromString(plaintext);
	}

	@Override
	public List<VaultDecryptionResult> decrypt(String keyName,
			List<Ciphertext> batchRequest) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.notEmpty(batchRequest,
				"BatchRequest must not be null and must have at least one entry");

		List<Map<String, String>> batch = new ArrayList<Map<String, String>>(
				batchRequest.size());

		for (Ciphertext request : batchRequest) {

			Map<String, String> vaultRequest = new LinkedHashMap<String, String>(2);

			vaultRequest.put("ciphertext", request.getCiphertext());

			if (request.getContext() != null) {
				applyTransitOptions(request.getContext(), vaultRequest);
			}

			batch.add(vaultRequest);
		}

		VaultResponse vaultResponse = vaultOperations.write(
				String.format("%s/decrypt/%s", path, keyName),
				Collections.singletonMap("batch_input", batch));

		return toDecryptionResults(vaultResponse, batchRequest);
	}

	@Override
	public String rewrap(String keyName, String ciphertext) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.hasText(ciphertext, "Ciphertext must not be empty");

		Map<String, String> request = new LinkedHashMap<>();
		request.put("ciphertext", ciphertext);

		return (String) vaultOperations
				.write(String.format("%s/rewrap/%s", path, keyName), request)
				.getRequiredData().get("ciphertext");
	}

	@Override
	public String rewrap(String keyName, String ciphertext,
			VaultTransitContext transitContext) {

		Assert.hasText(keyName, "KeyName must not be empty");
		Assert.hasText(ciphertext, "Ciphertext must not be empty");
		Assert.notNull(transitContext, "VaultTransitContext must not be null");

		Map<String, String> request = new LinkedHashMap<>();

		request.put("ciphertext", ciphertext);

		applyTransitOptions(transitContext, request);

		return (String) vaultOperations
				.write(String.format("%s/rewrap/%s", path, keyName), request)
				.getRequiredData().get("ciphertext");
	}

	private static void applyTransitOptions(VaultTransitContext context,
			Map<String, String> request) {

		if (!ObjectUtils.isEmpty(context.getContext())) {
			request.put("context", Base64Utils.encodeToString(context.getContext()));
		}

		if (!ObjectUtils.isEmpty(context.getNonce())) {
			request.put("nonce", Base64Utils.encodeToString(context.getNonce()));
		}
	}

	private static List<VaultEncryptionResult> toEncryptionResults(
			VaultResponse vaultResponse, List<Plaintext> batchRequest) {

		List<VaultEncryptionResult> result = new ArrayList<VaultEncryptionResult>(
				batchRequest.size());
		List<Map<String, String>> batchData = getBatchData(vaultResponse);

		for (int i = 0; i < batchRequest.size(); i++) {

			VaultEncryptionResult encrypted;
			Plaintext plaintext = batchRequest.get(i);
			if (batchData.size() > i) {

				Map<String, String> data = batchData.get(i);
				if (StringUtils.hasText(data.get("error"))) {
					encrypted = new VaultEncryptionResult(new VaultException(
							data.get("error")));
				}
				else {
					encrypted = new VaultEncryptionResult(toCiphertext(
							data.get("ciphertext"), plaintext.getContext()));
				}
			}
			else {
				encrypted = new VaultEncryptionResult(new VaultException(
						"No result for plaintext #" + i));
			}

			result.add(encrypted);
		}

		return result;
	}

	private static List<VaultDecryptionResult> toDecryptionResults(
			VaultResponse vaultResponse, List<Ciphertext> batchRequest) {

		List<VaultDecryptionResult> result = new ArrayList<VaultDecryptionResult>(
				batchRequest.size());
		List<Map<String, String>> batchData = getBatchData(vaultResponse);

		for (int i = 0; i < batchRequest.size(); i++) {

			VaultDecryptionResult encrypted;
			Ciphertext ciphertext = batchRequest.get(i);
			if (batchData.size() > i) {

				Map<String, String> data = batchData.get(i);
				if (StringUtils.hasText(data.get("error"))) {
					encrypted = new VaultDecryptionResult(new VaultException(
							data.get("error")));
				}
				else {
					encrypted = new VaultDecryptionResult(toPlaintext(
							Base64Utils.decodeFromString(data.get("plaintext")),
							ciphertext.getContext()));
				}
			}
			else {
				encrypted = new VaultDecryptionResult(new VaultException(
						"No result for ciphertext #" + i));
			}

			result.add(encrypted);
		}

		return result;
	}

	private static Ciphertext toCiphertext(String ciphertext, VaultTransitContext context) {
		return context != null ? Ciphertext.of(ciphertext).with(context) : Ciphertext
				.of(ciphertext);
	}

	private static Plaintext toPlaintext(byte[] plaintext, VaultTransitContext context) {
		return context != null ? Plaintext.of(plaintext).with(context) : Plaintext
				.of(plaintext);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, String>> getBatchData(VaultResponse vaultResponse) {
		return (List<Map<String, String>>) vaultResponse.getRequiredData().get(
				"batch_results");
	}

	@Data
	static class VaultTransitKeyImpl implements VaultTransitKey {

		@JsonProperty("cipher_mode")
		private String cipherMode = "";

		@JsonProperty("type")
		@Nullable
		private String type;

		@JsonProperty("deletion_allowed")
		private boolean deletionAllowed;

		private boolean derived;

		private boolean exportable;

		private Map<String, Object> keys = Collections.emptyMap();

		@JsonProperty("latest_version")
		private boolean latestVersion;

		@JsonProperty("min_decryption_version")
		private int minDecryptionVersion;

		@Nullable
		private String name;

		@Override
		public String getType() {

			if (this.type != null) {
				return this.type;
			}

			return this.cipherMode;
		}
	}

	@Data
	static class RawTransitKeyImpl implements RawTransitKey {

		private Map<String, String> keys = Collections.emptyMap();

		@Nullable
		private String name;
	}

}
