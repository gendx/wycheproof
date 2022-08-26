/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.security.wycheproof;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for RSA-PSS.
 */
@RunWith(JUnit4.class)
public class RsaPssTest {

  /**
   * Returns a PSSParameterSpec.
   *
   * @param sha the name of the hash function for hashing the input (e.g. "SHA-256")
   * @param mgf the name of the mask generating function (typically "MGF1")
   * @param mgfSha the name of the hash function for the mask generating function (typically the
   *     same as sha).
   * @param saltLen the length of the salt in bytes (typically the digest size of sha, i.e. 32 for
   *     "SHA-256")
   * @throws NoSuchAlgorithmException if the ParameterSpec could not be constructed.
   */
  protected static PSSParameterSpec getPssParameterSpec(
      String sha, String mgf, String mgfSha, int saltLen, int trailerField)
      throws NoSuchAlgorithmException {
    if (mgf.equals("MGF1")) {
      return new PSSParameterSpec(sha, mgf, new MGF1ParameterSpec(mgfSha), saltLen, trailerField);
    } else {
      throw new NoSuchAlgorithmException("Unknown MGF:" + mgf);
    }
  }

  /**
   * Converts PSS parameters to a string.
   *
   * <p>This function can be used to check that two parameter sets are equal. Comparing two
   * parameter sets assumes that MGF1 is being used. This is probably OK, since MGF1 is the only
   * existing mask generation function, and newer proposals (such as the one using SHAKE), use OIDs
   * that uniquely specify all the algorithm parameters.
   *
   * @param spec the PSS parameters.
   * @return a readable representation of spec.
   */
  private static String pssParameterSpecToString(PSSParameterSpec spec) {
    StringBuilder res = new StringBuilder();
    res.append("digestAlgorithm:")
        .append(spec.getDigestAlgorithm())
        .append("\nmgfAlgorithm:")
        .append(spec.getMGFAlgorithm())
        .append("\nsaltLength:")
        .append(spec.getSaltLength())
        .append("\ntrailerField:")
        .append(spec.getTrailerField());
    if (spec.getMGFAlgorithm().equals("MGF1")) {
      MGF1ParameterSpec mgf1Params = (MGF1ParameterSpec) spec.getMGFParameters();
      res.append("\nmgf1 digestAlgorithm:").append(mgf1Params.getDigestAlgorithm());
    }
    return res.toString();
  }

  /**
   * Returns an AlgorithmParameterSpec for generating a RSASSA-PSS key,
   * which include the PSSParameters.
   * Requires jdk11.
   *
   * @param keySizeInBits the size of the modulus in bits.
   * @param sha the name of the hash function for hashing the input (e.g. "SHA-256")
   * @param mgf the name of the mask generating function (typically "MGF1")
   * @param mgfSha the name of the hash function for the mask generating function
   *        (typically the same as sha).
   * @param saltLength the length of the salt in bytes (typically the digest size of sha,
   *        i.e. 32 for "SHA-256")
   */
  public RSAKeyGenParameterSpec getPssAlgorithmParameters(
      int keySizeInBits,
      String sha,
      String mgf,
      String mgfSha,
      int saltLength) {
    BigInteger publicExponent = new BigInteger("65537");
    PSSParameterSpec params =
        new PSSParameterSpec(sha, mgf, new MGF1ParameterSpec(mgfSha), saltLength, 1);
    return new RSAKeyGenParameterSpec(keySizeInBits, publicExponent, params);
  }

  /**
   * JCE often uses compact names for message digests. For example "SHA-256" is shortened to
   * "SHA256" in algorithm names such as "SHA256WITHRSAandMGF1". See
   * https://docs.oracle.com/en/java/javase/11/docs/specs/security/standard-names.html
   */
  protected static String compactDigestName(String md) {
    switch (md) {
      case "SHA-1":
        return "SHA1";
      case "SHA-224":
        return "SHA224";
      case "SHA-256":
        return "SHA256";
      case "SHA-384":
        return "SHA384";
      case "SHA-512":
        return "SHA512";
      case "SHA-512/224":
        return "SHA512/224";
      case "SHA-512/256":
        return "SHA512/256";
      // RSA-PSS with SHA-3 does not yet have standard algorithm names, hence the naming is unclear.
      // For other algorithms names such as "SHA3-224", "SHA3-256", "SHA3-384" and "SHA3-512" are
      // not modified. E.g. SHA3-256withRSA is a valid algorithm name.
      default:
        return md;
    }
  }

  /**
   * Returns the algorithm name for the RSA-PSS signature scheme. Oracle previously specified that
   * algorithm names for RSA-PSS are strings like "SHA256WITHRSAandMGF1". See
   * https://docs.oracle.com/en/java/javase/11/docs/specs/security/standard-names.html These
   * algorithm names fail to specify the hash function for the MGF. A cleaner solution in jdk11 is
   * to use the algorithm name "RSASSA-PSS" and specify the parameters separately. Conscrypt uses
   * algorithm names such as "SHA256withRSA/PSS", which are incompatible with other providers. This
   * function simply attempts to return an algorithm name that works. BouncyCastle allows to use
   * "RSASSA-PSS" or "SHA256withRSAandMGF1".
   *
   * @param sha the message digest (e.g. "SHA-256")
   * @param mgf the mask generation function (e.g. "MGF1")
   * @return the algorithm name
   * @throws NoSuchAlgorithmException if no algorithm name was found
   */
  private static String getAlgorithmName(String sha, String mgf) throws NoSuchAlgorithmException {
    try {
      Signature.getInstance("RSASSA-PSS");
      return "RSASSA-PSS";
    } catch (NoSuchAlgorithmException ex) {
      // RSASSA-PSS is not known. Try the other options.
    }
    String md = compactDigestName(sha);
    try {
      // Try the legacy naming for JCE.
      String name = md + "WITHRSAand" + mgf;
      Signature.getInstance(name);
      return name;
    } catch (NoSuchAlgorithmException ex) {
      // name is not supported. Try other options.
    }
    String name = md + "withRSA/PSS";
    Signature.getInstance(name);
    return name;
  }

  /**
   * Tries decoding an RSASSA-PSS key with algorithm parameters.
   *
   * <p>RFC 8017 Section A.2 defines algorithm identifiers to use for RSA keys. I.e., it defines the
   * following OIDs:
   *
   * <pre>
   *   PKCS1Algorithms    ALGORITHM-IDENTIFIER ::= {
   *    { OID rsaEncryption                PARAMETERS NULL } |
   *    { OID md2WithRSAEncryption         PARAMETERS NULL } |
   *    { OID md5WithRSAEncryption         PARAMETERS NULL } |
   *    { OID sha1WithRSAEncryption        PARAMETERS NULL } |
   *    { OID sha224WithRSAEncryption      PARAMETERS NULL } |
   *    { OID sha256WithRSAEncryption      PARAMETERS NULL } |
   *    { OID sha384WithRSAEncryption      PARAMETERS NULL } |
   *    { OID sha512WithRSAEncryption      PARAMETERS NULL } |
   *    { OID sha512-224WithRSAEncryption  PARAMETERS NULL } |
   *    { OID sha512-256WithRSAEncryption  PARAMETERS NULL } |
   *    { OID id-RSAES-OAEP   PARAMETERS RSAES-OAEP-params } |
   *    PKCS1PSourceAlgorithms                               |
   *    { OID id-RSASSA-PSS   PARAMETERS RSASSA-PSS-params },
   *    ...  -- Allows for future expansion --
   *  }
   * </pre>
   *
   * <p>Commonly, RSA keys don't use these algorithm identifiers, i.e., they simply use the object
   * identifier rsaEncryption regardless of the purpose of the key.
   *
   * <p>The object identifier id-RSASSA-PSS would be much more suitable for RSASSA-PSS keys, since
   * in this case all the algorithm parameters are included in the key. Unfortunately, RSA keys with
   * this object identifier are not widely supported.
   *
   * <p>OpenJdk allows to use keys both types of keys. The support of id-RSASSA-PSS is newer. A
   * drawback is that the caller has to know the encoding of the RSA keys:
   * RSA keys with object identifier rsaEncryption require to use KeyFactory.getInstance("RSA"),
   * whereas keys with object identifier id-RSASSA-PSS require to use
   * KeyFactory.getInstance("RSASSA-PSS").
   *
   * <p>The main purpose of the test below is simply to check if a provider supports RSA keys with
   * id-RSASSA-PSS.
   *
   * <p>The test passes if id-RSASSA-PSS is supported. The test is skipped if such keys are not
   * supported or if a provider uses alternative algorithm names for KeyFactory. The test fails
   * (rsp. should fail) if KeyFactory.getInstance("RSASSA-PSS") is supported but does not accept
   * keys with OID id-RSASSA-PSS or if the test results in incorrect parameters.
   */
  @Test
  public void testDecodePublicKeyWithPssParameters() throws Exception {
    String sha = "SHA-256";
    String mgf = "MGF1";
    int saltLength = 20;
    PSSParameterSpec expectedParams =
        new PSSParameterSpec(sha, mgf, new MGF1ParameterSpec(sha), saltLength, 1);
    // Below is an RSA key where the algorithm parameter use the object
    // identifier id-RSASSA-PSS together with the message digest SHA-256,
    // MGF1 and saltLength 20.
    String encodedPubKey =
        "30820151303c06092a864886f70d01010a302fa00f300d060960864801650304"
            + "02010500a11c301a06092a864886f70d010108300d0609608648016503040201"
            + "05000382010f003082010a0282010100b09191ef91e8b4ab58f7c66430636641"
            + "0988d8cba6f2e0f33495d37b355828d04554472e854dff7d8c1dfd1ea50123de"
            + "12d34b77280220184b924db82a535978e9bfe7a6111f455028f18cd923c54144"
            + "08a247409d7121a99c3594708c0dd9cdebf1c9bb0060ff1c4c0363e25fac0d5b"
            + "bf85013945f393b0b9673780c6f579353ae895d7dc891220a92bac0a8deb35b5"
            + "20803cf82b19c27232a889d0f04fb2bde6623f357e3e56027298379d10bee8fa"
            + "4e0c29029a78fde01694719d2d036fe726aa5633205553565f127a78fec46918"
            + "182e41a16c5cc86bd3b77d26c5113082cb1f2d83d9213eca019bbdee99001e11"
            + "16bcfec1242ece175558b15c5bbbc4710203010001";
    RSAPublicKey pubKey;
    try {
      KeyFactory kf = KeyFactory.getInstance("RSASSA-PSS");
      X509EncodedKeySpec spec = new X509EncodedKeySpec(TestUtil.hexToBytes(encodedPubKey));
      pubKey = (RSAPublicKey) kf.generatePublic(spec);
    } catch (NoSuchAlgorithmException ex) {
      TestUtil.skipTest("RSASSA-PSS keys with parameters are not supported.");
      return;
    }
    AlgorithmParameterSpec params = pubKey.getParams();
    if (params == null) {
      // No parameters found.
      // This means that the algorithm parameters in the encoded key have
      // been ignored. Incompatible implementations are a likely consequence
      // of this.
      fail("getParameters is null");
    }
    PSSParameterSpec pssParams = (PSSParameterSpec) params;
    String found = pssParameterSpecToString(pssParams);
    String expected = pssParameterSpecToString(expectedParams);
    assertEquals(expected, found);
  }

  /**
   * Tries encoding and decoding of RSASSA-PSS keys generated with RSASSA-PSS.
   *
   * <p>RSASSA-PSS keys contain the PSSParameters, hence their encodings are somewhat different than
   * plain RSA keys.
   */
  @Test
  public void testEncodeDecodePublic() throws Exception {
    int keySizeInBits = 2048;
    PublicKey pub;
    KeyFactory kf;
    try {
      kf = KeyFactory.getInstance("RSASSA-PSS");
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSASSA-PSS");
      keyGen.initialize(keySizeInBits);
      KeyPair keypair = keyGen.genKeyPair();
      pub = keypair.getPublic();
    } catch (NoSuchAlgorithmException ex) {
      TestUtil.skipTest("Key generation for RSASSA-PSS is not supported.");
      return;
    }
    byte[] encoded = pub.getEncoded();
    assertEquals(
        "The test assumes that the public key is in X.509 format", "X.509", pub.getFormat());
    X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
    kf.generatePublic(spec);
  }

  /**
   * Tries encoding and decoding of RSASSA-PSS keys generated with RSASSA-PSS.
   *
   * <p>RSASSA-PSS keys contain the PSSParameters, hence their encodings are somewhat different than
   * plain RSA keys.
   */
  @Test
  public void testEncodeDecodePublicWithParameters() throws Exception {
    int keySizeInBits = 2048;
    PublicKey pub;
    String sha = "SHA-256";
    String mgf = "MGF1";
    int saltLength = 20;
    try {
      RSAKeyGenParameterSpec params =
          getPssAlgorithmParameters(keySizeInBits, sha, mgf, sha, saltLength);
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSASSA-PSS");
      keyGen.initialize(params);
      KeyPair keypair = keyGen.genKeyPair();
      pub = keypair.getPublic();
    } catch (NoSuchAlgorithmException ex) {
      TestUtil.skipTest("Key generation for RSASSA-PSS is not supported.");
      return;
    }
    byte[] encoded = pub.getEncoded();
    X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
    KeyFactory kf = KeyFactory.getInstance("RSASSA-PSS");
    kf.generatePublic(spec);
  }

  /**
   * Tests the default parameters used for a given algorithm name.
   *
   * @param algorithm the algorithm name for an RSA-PSS instance. (e.g. "SHA256WithRSAandMGF1")
   * @param expectedHash the hash algorithm expected for the given algorithm
   * @param expectedMgf the mask generation function expected for the given algorithm (e.g. "MGF1")
   * @param expectedMgfHash the hash algorithm exptected for the mask generation function
   * @param expectedSaltLength the expected salt length in bytes for the given algorithm
   * @param expectedTrailerField the expected value for the tailer field (e.g. 1 for 0xbc).
   */
  protected void testDefaultForAlgorithm(
      String algorithm,
      String expectedHash,
      String expectedMgf,
      String expectedMgfHash,
      int expectedSaltLength,
      int expectedTrailerField) throws Exception {
    // An X509 encoded 2048-bit RSA public key.
    String pubKey =
        "30820122300d06092a864886f70d01010105000382010f003082010a02820101"
            + "00bdf90898577911c71c4d9520c5f75108548e8dfd389afdbf9c997769b8594e"
            + "7dc51c6a1b88d1670ec4bb03fa550ba6a13d02c430bfe88ae4e2075163017f4d"
            + "8926ce2e46e068e88962f38112fc2dbd033e84e648d4a816c0f5bd89cadba0b4"
            + "d6cac01832103061cbb704ebacd895def6cff9d988c5395f2169a6807207333d"
            + "569150d7f569f7ebf4718ddbfa2cdbde4d82a9d5d8caeb467f71bfc0099b0625"
            + "a59d2bad12e3ff48f2fd50867b89f5f876ce6c126ced25f28b1996ee21142235"
            + "fb3aef9fe58d9e4ef6e4922711a3bbcd8adcfe868481fd1aa9c13e5c658f5172"
            + "617204314665092b4d8dca1b05dc7f4ecd7578b61edeb949275be8751a5a1fab"
            + "c30203010001";
    KeyFactory kf;
    kf = KeyFactory.getInstance("RSA");
    X509EncodedKeySpec x509keySpec = new X509EncodedKeySpec(TestUtil.hexToBytes(pubKey));
    PublicKey key = kf.generatePublic(x509keySpec);
    Signature verifier;
    try {
      verifier = Signature.getInstance(algorithm);
      verifier.initVerify(key);
    } catch (NoSuchAlgorithmException ex) {
      System.out.println("Unsupported algorithm:" + algorithm);
      return;
    }
    AlgorithmParameters params = verifier.getParameters();
    if (params == null) {
      // No defaults are specified. This is a good choice since this avoid
      // incompatible implementations.
      return;
    }
    PSSParameterSpec pssParams = params.getParameterSpec(PSSParameterSpec.class);
    assertEquals("digestAlgorithm", expectedHash, pssParams.getDigestAlgorithm());
    assertEquals("mgfAlgorithm", expectedMgf, pssParams.getMGFAlgorithm());
    assertEquals("saltLength", expectedSaltLength, pssParams.getSaltLength());
    assertEquals("trailerField", expectedTrailerField, pssParams.getTrailerField());
    if (expectedMgf.equals("MGF1")) {
      MGF1ParameterSpec mgf1Params = (MGF1ParameterSpec) pssParams.getMGFParameters();
      assertEquals("mgf1 digestAlgorithm", expectedMgfHash, mgf1Params.getDigestAlgorithm());
    }
  }

  /**
   * Tests the default values for PSS parameters.
   *
   * <p>RSA-PSS has a number of parameters. RFC 8017 specifies the parameters as follows:
   *
   * <pre>
   * RSASSA-PSS-params :: = SEQUENCE {
   *   hashAlgorithm            [0] HashAlgorithm     DEFAULT sha1,
   *   maskGenerationAlgorithm  [1] MaskGenAlgorithm  DEFAULT mgf1SHA1,
   *   saltLength               [2] INTEGER           DEFAULT 20,
   *   trailerField             [3] TrailerField      DEFAULT trailerFieldBC
   * }
   * </pre>
   *
   * <p>The algorithm name for RSA-PSS used in jdk11 is "RSASSA-PSS". Previously, the algorithm
   * names for RSA-PSS were defined in the section "Signature Algorithms" of
   * https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html
   * I.e. the proposed standard names had the format <digest>with<encryption>and<mgf>, e.g.,
   * SHA256withRSAandMGF1. This name only specifies the hashAlgorithm and the mask generation
   * algorithm, but not the hash used for the mask generation algorithm, the salt length and
   * the trailerField. The missing parameters can be explicitly specified with and instance
   * of PSSParameterSpec. The test below checks that distinct providers use the same default values
   * when no PSSParameterSpec is given.
   *
   * <p>In particular, the test expects that the two hash algorithm (for message hashing and mgf)
   * are the same. It expects that the saltLength is the same as the size of the message digest.
   * It expects that the default for the trailerField is 1. These expectations are based on
   * existing implementations. They differ from the ASN defaults in RFC 8017.
   *
   * <p>There is no test for defaults for the algorithm name "RSASSA-PSS".
   * "RSASSA-PSS" does not specify any parameters. Using the default values from RFC 8017
   * (i.e. SHA-1 for both hashes) leads to potential weaknesses and hence is of course a bad
   * choice. Other defaults lead to incompatibilities and hence isn't a reasonable choice either.
   * jdk11 requires that the parameters are always specified. BouncyCastle however uses the SHA-1
   * default. The behaviour in jdk11 is preferable, since it requires that an implementor chooses
   * PSSParameters explicitly, and does not default to weak behaviour.
   */
  @Test
  public void testDefaults() throws Exception {
    testDefaultForAlgorithm("SHA1withRSAandMGF1", "SHA-1", "MGF1", "SHA-1", 20, 1);
    testDefaultForAlgorithm("SHA224withRSAandMGF1", "SHA-224", "MGF1", "SHA-224", 28, 1);
    testDefaultForAlgorithm("SHA256withRSAandMGF1", "SHA-256", "MGF1", "SHA-256", 32, 1);
    testDefaultForAlgorithm("SHA384withRSAandMGF1", "SHA-384", "MGF1", "SHA-384", 48, 1);
    testDefaultForAlgorithm("SHA512withRSAandMGF1", "SHA-512", "MGF1", "SHA-512", 64, 1);
    testDefaultForAlgorithm(
        "SHA512/224withRSAandMGF1", "SHA-512/224", "MGF1", "SHA-512/224", 28, 1);
    testDefaultForAlgorithm(
        "SHA512/256withRSAandMGF1", "SHA-512/256", "MGF1", "SHA-512/256", 32, 1);
    testDefaultForAlgorithm("SHA3-224withRSAandMGF1", "SHA3-224", "MGF1", "SHA3-224", 28, 1);
    testDefaultForAlgorithm("SHA3-256withRSAandMGF1", "SHA3-256", "MGF1", "SHA3-256", 32, 1);
    testDefaultForAlgorithm("SHA3-384withRSAandMGF1", "SHA3-384", "MGF1", "SHA3-384", 48, 1);
    testDefaultForAlgorithm("SHA3-512withRSAandMGF1", "SHA3-512", "MGF1", "SHA3-512", 64, 1);
  }

  /**
   * RSA-PSS is a randomized signature scheme (unless the length of the salt is 0).
   *
   * <p>This test checks that RSA-PSS signatures are randomized. Failing to properly randomize
   * RSA-PSS signatures is not a critical mistake. But doing so may reduce the security of RSA-PSS
   * signatures. (see e.g. RFC 8017, Section 8.1).
   */
  @Test
  public void testRandomization() throws Exception {
    String sha = "SHA-256";
    String mgf = "MGF1";
    int saltLen = 32;
    int keySizeInBits = 2048;
    int samples = 8;
    Signature signer;
    PrivateKey priv;
    try {
      String algorithm = getAlgorithmName(sha, mgf);
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
      keyGen.initialize(keySizeInBits);
      KeyPair keypair = keyGen.genKeyPair();
      priv = keypair.getPrivate();
      signer = Signature.getInstance(algorithm);
      PSSParameterSpec params = getPssParameterSpec(sha, mgf, sha, saltLen, 1);
      signer.setParameter(params);
    } catch (NoSuchAlgorithmException ex) {
      TestUtil.skipTest("RSA key generation is not supported.");
      return;
    }
    Set<String> signatures = new HashSet<String>();
    byte[] messageBytes = new byte[8];
    for (int i = 0; i < samples; i++) {
      signer.initSign(priv);
      signer.update(messageBytes);
      byte[] signature = signer.sign();
      String hex = TestUtil.bytesToHex(signature);
      assertTrue("Same signature computed twice", signatures.add(hex));
    }
  }

  /**
   * Tests RSA-PSS when initialized with null instead of a SecureRandom instance.
   *
   * <p>The expected behaviour is that Signature.initSign(RSAPrivateKey, null) behaves simlar to
   * Signature.initSign(RsaPrivateKey), i.e. that in both cases a default instance of SecureRandom
   * is used. Similar to testRandomization() a failure to set a random seed would not be critical.
   *
   * <p>The test also verifies that Signature.initSign(RSAPrivateKey, null) does not throw an
   * exception. Throwing a NullPointerException would not violate any contracts (as far as I know).
   * However, some applications expect that null is valid an most provider use a default instance of
   * SecureRandom. Hence not accepting null would likely lead to incompatibilities.
   *
   * <p>See also: https://github.com/bcgit/bc-java/pull/632
   */
  @Test
  public void testNullRandom() throws Exception {
    String sha = "SHA-256";
    String mgf = "MGF1";
    int saltLen = 32;
    int keySizeInBits = 2048;
    int samples = 8;
    Signature signer;
    PrivateKey priv;
    try {
      String algorithm = getAlgorithmName(sha, mgf);
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
      keyGen.initialize(keySizeInBits);
      KeyPair keypair = keyGen.genKeyPair();
      priv = keypair.getPrivate();
      signer = Signature.getInstance(algorithm);
      PSSParameterSpec params = getPssParameterSpec(sha, mgf, sha, saltLen, 1);
      signer.setParameter(params);
    } catch (NoSuchAlgorithmException ex) {
      TestUtil.skipTest("RSA key generation is not supported.");
      return;
    }
    Set<String> signatures = new HashSet<String>();
    byte[] messageBytes = new byte[8];
    for (int i = 0; i < samples; i++) {
      signer.initSign(priv, null);
      signer.update(messageBytes);
      byte[] signature = signer.sign();
      String hex = TestUtil.bytesToHex(signature);
      assertTrue("Same signature computed twice", signatures.add(hex));
    }
  }
}

