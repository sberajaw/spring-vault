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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.vault.VaultException;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.support.RawTransitKey;
import org.springframework.vault.support.TransitKeyType;
import org.springframework.vault.support.VaultDecryptionResult;
import org.springframework.vault.support.VaultEncryptionResult;
import org.springframework.vault.support.VaultMount;
import org.springframework.vault.support.VaultTransitContext;
import org.springframework.vault.support.VaultTransitKey;
import org.springframework.vault.support.VaultTransitKeyConfiguration;
import org.springframework.vault.support.VaultTransitKeyCreationRequest;
import org.springframework.vault.util.IntegrationTestSupport;
import org.springframework.vault.util.Version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Integration tests for {@link VaultTransitTemplate} through
 * {@link VaultTransitOperations}.
 *
 * @author Mark Paluch
 * @author Praveendra Singh
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = VaultIntegrationTestConfiguration.class)
public class VaultTransitTemplateIntegrationTests extends IntegrationTestSupport {

	private static final Version BATCH_INTRODUCED_IN_VERSION = Version.parse("0.6.5");

	@Autowired
	private VaultOperations vaultOperations;

	private VaultTransitOperations transitOperations;

	private Version vaultVersion;

	@Before
	public void before() {

		transitOperations = vaultOperations.opsForTransit();

		if (!vaultOperations.opsForSys().getMounts().containsKey("transit/")) {
			vaultOperations.opsForSys().mount("transit", VaultMount.create("transit"));
		}

		vaultVersion = prepare().getVersion();

		removeKeys();
	}

	@After
	public void tearDown() {
		removeKeys();
	}

	private void deleteKey(String keyName) {

		try {
			transitOperations.configureKey(keyName, VaultTransitKeyConfiguration
					.builder().deletionAllowed(true).build());
		}
		catch (Exception e) {
		}

		try {
			transitOperations.deleteKey(keyName);
		}
		catch (Exception e) {
		}
	}

	private void removeKeys() {

		if (vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.4"))) {
			List<String> keys = vaultOperations.opsForTransit().getKeys();
			for (String keyName : keys) {
				deleteKey(keyName);
			}
		}
		else {
			deleteKey("mykey");
			deleteKey("export");
			deleteKey("ecdsa-key");
			deleteKey("ed-key");
		}
	}

	@Test
	public void createKeyShouldCreateKey() {

		transitOperations.createKey("mykey");

		VaultTransitKey mykey = transitOperations.getKey("mykey");

		assertThat(mykey.getType()).startsWith("aes");

		assertThat(mykey.getName()).isEqualTo("mykey");
		assertThat(mykey.isDeletionAllowed()).isFalse();
		assertThat(mykey.isDerived()).isFalse();
		assertThat(mykey.getMinDecryptionVersion()).isEqualTo(1);
		assertThat(mykey.isLatestVersion()).isTrue();
	}

	@Test
	public void createKeyShouldCreateEcDsaKey() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.4")));

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest
				.ofKeyType("ecdsa-p256");

		transitOperations.createKey("ecdsa-key", request);

		VaultTransitKey mykey = transitOperations.getKey("ecdsa-key");

		assertThat(mykey.getType()).startsWith("ecdsa");
		assertThat(mykey.getKeys()).isNotEmpty();
	}

	@Test
	public void createKeyShouldCreateEdKey() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.8.0")));

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest
				.ofKeyType("ed25519");

		transitOperations.createKey("ed-key", request);

		VaultTransitKey mykey = transitOperations.getKey("ed-key");

		assertThat(mykey.getType()).startsWith("ed");
		assertThat(mykey.getKeys()).isNotEmpty();
	}

	@Test
	public void createKeyShouldCreateKeyWithOptions() {

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.convergentEncryption(true) //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		VaultTransitKey mykey = transitOperations.getKey("mykey");

		assertThat(mykey.getName()).isEqualTo("mykey");
		assertThat(mykey.isDeletionAllowed()).isFalse();
		assertThat(mykey.isDerived()).isTrue();
		assertThat(mykey.getMinDecryptionVersion()).isEqualTo(1);
		assertThat(mykey.isLatestVersion()).isTrue();
	}

	@Test
	public void shouldEnumerateKey() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.4")));

		assertThat(transitOperations.getKeys()).isEmpty();

		transitOperations.createKey("mykey");

		assertThat(transitOperations.getKeys()).contains("mykey");
	}

	@Test
	public void getKeyShouldReturnNullIfKeyNotExists() {

		VaultTransitKey key = transitOperations.getKey("hello-world");
		assertThat(key).isNull();
	}

	@Test
	public void deleteKeyShouldFailIfKeyNotExists() {

		try {
			transitOperations.deleteKey("hello-world");
			fail("Missing VaultException");
		}
		catch (VaultException e) {
			assertThat(e).hasMessageContaining("Status 400");
		}
	}

	@Test
	public void deleteKeyShouldDeleteKey() {

		transitOperations.createKey("mykey");
		transitOperations.configureKey("mykey", VaultTransitKeyConfiguration.builder()
				.deletionAllowed(true).build());
		transitOperations.deleteKey("mykey");

		assertThat(transitOperations.getKey("mykey")).isNull();
	}

	@Test
	public void encryptShouldCreateCiphertext() {

		transitOperations.createKey("mykey");

		String ciphertext = transitOperations.encrypt("mykey", "hello-world");
		assertThat(ciphertext).startsWith("vault:v");
	}

	@Test
	public void encryptShouldCreateCiphertextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext transitRequest = VaultTransitContext.builder()
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		String ciphertext = transitOperations.encrypt("mykey", "hello-world".getBytes(),
				transitRequest);
		assertThat(ciphertext).startsWith("vault:v1:");
	}

	@Test
	public void encryptShouldCreateWrappedCiphertextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext context = VaultTransitContext.builder()
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		Ciphertext ciphertext = transitOperations.encrypt("mykey",
				Plaintext.of("hello-world").with(context));

		assertThat(ciphertext.getCiphertext()).startsWith("vault:v1:");
		assertThat(ciphertext.getContext()).isEqualTo(context);
	}

	@Test
	public void decryptShouldCreatePlaintext() {

		transitOperations.createKey("mykey");

		String ciphertext = transitOperations.encrypt("mykey", "hello-world");
		String plaintext = transitOperations.decrypt("mykey", ciphertext);

		assertThat(plaintext).isEqualTo("hello-world");
	}

	@Test
	public void decryptShouldCreatePlaintextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext transitRequest = VaultTransitContext.builder() //
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		String ciphertext = transitOperations.encrypt("mykey", "hello-world".getBytes(),
				transitRequest);

		byte[] plaintext = transitOperations.decrypt("mykey", ciphertext, transitRequest);
		assertThat(new String(plaintext)).isEqualTo("hello-world");
	}

	@Test
	public void decryptShouldCreateWrappedPlaintextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext context = VaultTransitContext.builder() //
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		Ciphertext ciphertext = transitOperations.encrypt("mykey",
				Plaintext.of("hello-world").with(context));
		Plaintext plaintext = transitOperations.decrypt("mykey", ciphertext);

		assertThat(plaintext.asString()).isEqualTo("hello-world");
		assertThat(plaintext.getContext()).isEqualTo(context);
	}

	@Test
	public void encryptAndRewrapShouldCreateCiphertext() {

		transitOperations.createKey("mykey");

		String ciphertext = transitOperations.encrypt("mykey", "hello-world");
		transitOperations.rotate("mykey");

		String rewrapped = transitOperations.rewrap("mykey", ciphertext);

		assertThat(rewrapped).startsWith("vault:v2:");
	}

	@Test
	public void shouldEncryptBinaryPlaintext() {

		transitOperations.createKey("mykey");

		byte[] plaintext = new byte[] { 1, 2, 3, 4, 5 };

		String ciphertext = transitOperations.encrypt("mykey", plaintext,
				VaultTransitContext.empty());

		byte[] decrypted = transitOperations.decrypt("mykey", ciphertext,
				VaultTransitContext.empty());

		assertThat(decrypted).isEqualTo(plaintext);
	}

	@Test
	public void encryptAndRewrapShouldCreateCiphertextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext transitRequest = VaultTransitContext.builder() //
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		String ciphertext = transitOperations.encrypt("mykey", "hello-world".getBytes(),
				transitRequest);
		transitOperations.rotate("mykey");

		String rewrapped = transitOperations.rewrap("mykey", ciphertext, transitRequest);
		assertThat(rewrapped).startsWith("vault:v2");
	}

	@Test
	public void shouldBatchEncrypt() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(BATCH_INTRODUCED_IN_VERSION));

		transitOperations.createKey("mykey");

		List<VaultEncryptionResult> encrypted = transitOperations.encrypt("mykey",
				Arrays.asList(Plaintext.of("one"), Plaintext.of("two")));

		assertThat(encrypted.get(0).get().getCiphertext()).startsWith("vault:");
		assertThat(encrypted.get(1).get().getCiphertext()).startsWith("vault:");
	}

	@Test
	public void shouldBatchDecrypt() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(BATCH_INTRODUCED_IN_VERSION));

		transitOperations.createKey("mykey");

		Ciphertext one = transitOperations.encrypt("mykey", Plaintext.of("one"));
		Ciphertext two = transitOperations.encrypt("mykey", Plaintext.of("two"));

		Plaintext plainOne = transitOperations.decrypt("mykey", one);
		Plaintext plainTwo = transitOperations.decrypt("mykey", two);

		List<VaultDecryptionResult> decrypted = transitOperations.decrypt("mykey",
				Arrays.asList(one, two));

		assertThat(decrypted.get(0).get()).isEqualTo(plainOne);
		assertThat(decrypted.get(0).getAsString()).isEqualTo("one");
		assertThat(decrypted.get(1).get()).isEqualTo(plainTwo);
		assertThat(decrypted.get(1).getAsString()).isEqualTo("two");
	}

	@Test
	public void shouldBatchEncryptWithContext() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(BATCH_INTRODUCED_IN_VERSION));

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		Plaintext one = Plaintext.of("one").with(
				VaultTransitContext.builder().context("oneContext".getBytes()).build());

		Plaintext two = Plaintext.of("two").with(
				VaultTransitContext.builder().context("twoContext".getBytes()).build());

		List<VaultEncryptionResult> encrypted = transitOperations.encrypt("mykey",
				Arrays.asList(one, two));

		assertThat(encrypted.get(0).get().getContext()).isEqualTo(one.getContext());
		assertThat(encrypted.get(1).get().getContext()).isEqualTo(two.getContext());
	}

	@Test
	public void shouldBatchDecryptWithContext() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(BATCH_INTRODUCED_IN_VERSION));

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		Plaintext one = Plaintext.of("one").with(
				VaultTransitContext.builder().context("oneContext".getBytes()).build());

		Plaintext two = Plaintext.of("two").with(
				VaultTransitContext.builder().context("twoContext".getBytes()).build());

		List<VaultEncryptionResult> encrypted = transitOperations.encrypt("mykey",
				Arrays.asList(one, two));
		List<VaultDecryptionResult> decrypted = transitOperations.decrypt("mykey",
				Arrays.asList(encrypted.get(0).get(), encrypted.get(1).get()));

		assertThat(decrypted.get(0).get()).isEqualTo(one);
		assertThat(decrypted.get(1).get()).isEqualTo(two);
	}

	@Test
	public void shouldBatchDecryptWithWrongContext() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(BATCH_INTRODUCED_IN_VERSION));

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		Plaintext one = Plaintext.of("one").with(
				VaultTransitContext.builder().context("oneContext".getBytes()).build());

		Plaintext two = Plaintext.of("two").with(
				VaultTransitContext.builder().context("twoContext".getBytes()).build());

		List<VaultEncryptionResult> encrypted = transitOperations.encrypt("mykey",
				Arrays.asList(one, two));

		Ciphertext encryptedOne = encrypted.get(0).get();
		Ciphertext decryptedTwo = encrypted.get(1).get();

		Ciphertext tampered = decryptedTwo.with(encryptedOne.getContext());

		List<VaultDecryptionResult> decrypted = transitOperations.decrypt("mykey",
				Arrays.asList(encryptedOne, tampered));

		assertThat(decrypted.get(0).get()).isEqualTo(one);
		assertThat(decrypted.get(1).isSuccessful()).isEqualTo(false);
		assertThat(decrypted.get(1).getCause()).isInstanceOf(VaultException.class);
	}

	@Test
	public void shouldCreateNewExportableKey() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.5")));

		VaultTransitOperations vaultTransitOperations = vaultOperations.opsForTransit();
		VaultTransitKeyCreationRequest vaultTransitKeyCreationRequest = VaultTransitKeyCreationRequest
				.builder().exportable(true).derived(true).build();

		vaultTransitOperations.createKey("export-test", vaultTransitKeyCreationRequest);

		VaultTransitKey vaultTransitKey = vaultTransitOperations.getKey("export-test");

		assertThat(vaultTransitKey.getName()).isEqualTo("export-test");
		assertThat(vaultTransitKey.isExportable()).isTrue();
	}

	@Test
	public void shouldCreateNotExportableKeyByDefault() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.5")));

		VaultTransitOperations vaultTransitOperations = vaultOperations.opsForTransit();

		vaultTransitOperations.createKey("no-export");

		VaultTransitKey vaultTransitKey = vaultTransitOperations.getKey("no-export");

		assertThat(vaultTransitKey.getName()).isEqualTo("no-export");
		assertThat(vaultTransitKey.isExportable()).isFalse();
	}

	@Test
	public void shouldExportEncryptionKey() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.5")));

		vaultOperations.write("transit/keys/export",
				Collections.singletonMap("exportable", true));

		RawTransitKey rawTransitKey = transitOperations.exportKey("export",
				TransitKeyType.ENCRYPTION_KEY);

		assertThat(rawTransitKey.getName()).isEqualTo("export");
		assertThat(rawTransitKey.getKeys()).isNotEmpty();
		assertThat(rawTransitKey.getKeys().get("1")).isNotBlank();
	}

	@Test(expected = VaultException.class)
	public void shouldNotAllowExportSigningKey() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.5")));

		vaultOperations.write("transit/keys/export",
				Collections.singletonMap("exportable", true));

		transitOperations.exportKey("export", TransitKeyType.SIGNING_KEY);
	}

	@Test
	public void shouldExportHmacKey() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.5")));

		vaultOperations.write("transit/keys/export",
				Collections.singletonMap("exportable", true));

		RawTransitKey rawTransitKey = transitOperations.exportKey("export",
				TransitKeyType.HMAC_KEY);

		assertThat(rawTransitKey.getName()).isEqualTo("export");
		assertThat(rawTransitKey.getKeys()).isNotEmpty();
		assertThat(rawTransitKey.getKeys().get("1")).isNotBlank();
	}

	@Test
	public void shouldExportEcDsaKey() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.5")));

		VaultTransitOperations transitOperations = vaultOperations.opsForTransit();

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder()
				.type("ecdsa-p256").exportable(true).build();

		transitOperations.createKey("ecdsa-key", request);

		RawTransitKey hmacKey = transitOperations.exportKey("ecdsa-key",
				TransitKeyType.HMAC_KEY);
		RawTransitKey signingKey = transitOperations.exportKey("ecdsa-key",
				TransitKeyType.SIGNING_KEY);

		assertThat(hmacKey.getKeys()).isNotEmpty();
		assertThat(signingKey.getKeys()).isNotEmpty();
	}

	@Test
	public void shouldExportEdKey() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.8.0")));

		VaultTransitOperations transitOperations = vaultOperations.opsForTransit();

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder()
				.type("ed25519").exportable(true).build();

		transitOperations.createKey("ed-key", request);

		RawTransitKey hmacKey = transitOperations.exportKey("ed-key",
				TransitKeyType.HMAC_KEY);
		RawTransitKey signingKey = transitOperations.exportKey("ed-key",
				TransitKeyType.SIGNING_KEY);

		assertThat(hmacKey.getKeys()).isNotEmpty();
		assertThat(signingKey.getKeys()).isNotEmpty();
	}
}
