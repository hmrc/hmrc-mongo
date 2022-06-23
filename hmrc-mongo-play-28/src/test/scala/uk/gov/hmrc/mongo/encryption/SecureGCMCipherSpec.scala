/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.mongo.encryption

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.security.InvalidAlgorithmParameterException
import java.util.Base64
import javax.crypto.{Cipher, IllegalBlockSizeException, KeyGenerator, NoSuchPaddingException}
import javax.crypto.spec.GCMParameterSpec

class SecureGCMCipherSpec
  extends AnyWordSpecLike
     with Matchers {

  private val encrypter      = new SecureGCMCipher
  private val secretKey      = "VqmXp7yigDFxbCUdDdNZVIvbW6RgPNJsliv6swQNCL8="
  private val secretKey2     = "cXo7u0HuJK8B/52xLwW7eQ=="
  private val textToEncrypt  = "textNotEncrypted"
  private val associatedText = "associatedText"
  private val encryptedText = EncryptedValue(
    "jOrmajkEqb7Jbo1GvK4Mhc3E7UiOfKS3RCy3O/F6myQ=",
    "WM1yMH4KBGdXe65vl8Gzd37Ob2Bf1bFUSaMqXk78sNeorPFOSWwwhOj0Lcebm5nWRhjNgL4K2SV3GWEXyyqeIhWQ4fJIVQRHM9VjWCTyf7/1/f/ckAaMHqkF1XC8bnW9"
  )

  "encrypt" should {
    "encrypt some text" in {
      val encryptedValue = encrypter.encrypt(textToEncrypt, associatedText, secretKey)
      encryptedValue shouldBe an[EncryptedValue]
    }
  }

  "decrypt" should {
    "decrypt text when the same associatedText, nonce and secretKey were used to encrypt it" in {
      val decryptedText = encrypter.decrypt(encryptedText, associatedText, secretKey)
      decryptedText shouldEqual textToEncrypt
    }

    "return an EncryptionDecryptionException if the encrypted value is different" in {
      val invalidText           = Base64.getEncoder.encodeToString("invalid value".getBytes)
      val invalidEncryptedValue = EncryptedValue(invalidText, encryptedText.nonce)

      val decryptAttempt = intercept[EncryptionDecryptionException](
        encrypter.decrypt(invalidEncryptedValue, associatedText, secretKey)
      )

      decryptAttempt.failureReason should include("Error occurred due to padding scheme")
    }

    "return an EncryptionDecryptionException if the nonce is different" in {
      val invalidNonce          = Base64.getEncoder.encodeToString("invalid value".getBytes)
      val invalidEncryptedValue = EncryptedValue(encryptedText.value, invalidNonce)

      val decryptAttempt = intercept[EncryptionDecryptionException](
        encrypter.decrypt(invalidEncryptedValue, associatedText, secretKey)
      )

      decryptAttempt.failureReason should include("Error occurred due to padding scheme")
    }

    "return an EncryptionDecryptionException if the associated text is different" in {
      val decryptAttempt = intercept[EncryptionDecryptionException](
        encrypter.decrypt(encryptedText, "invalid associated text", secretKey)
      )

      decryptAttempt.failureReason should include("Error occurred due to padding scheme")
    }

    "return an EncryptionDecryptionException if the secret key is different" in {
      val decryptAttempt = intercept[EncryptionDecryptionException](
        encrypter.decrypt(encryptedText, associatedText, secretKey2)
      )

      decryptAttempt.failureReason should include("Error occurred due to padding scheme")
    }

    "return an EncryptionDecryptionException if the associated text is empty" in {
      val decryptAttempt = intercept[EncryptionDecryptionException](
        encrypter.decrypt(encryptedText, "", secretKey)
      )

      decryptAttempt.failureReason should include("associated text must not be null")
    }

    "return an EncryptionDecryptionException if the key is empty" in {
      val decryptAttempt = intercept[EncryptionDecryptionException](
        encrypter.decrypt(encryptedText, associatedText, "")
      )

      decryptAttempt.failureReason should include("The key provided is invalid")
    }

    "return an EncryptionDecryptionException if the key is invalid" in {
      val decryptAttempt = intercept[EncryptionDecryptionException](
        encrypter.decrypt(encryptedText, associatedText, "invalidKey")
      )

      decryptAttempt.failureReason should include(
        "Key being used is not valid." +
          " It could be due to invalid encoding, wrong length or uninitialized"
      )
    }

    "return an EncryptionDecryptionError if the secret key is an invalid type" in {
      val keyGen = KeyGenerator.getInstance("DES")
      val key    = keyGen.generateKey()
      val secureGCMEncryter = new SecureGCMCipher {
        override val ALGORITHM_KEY: String = "DES"
      }
      val encryptedAttempt = intercept[EncryptionDecryptionException](
        secureGCMEncryter.generateCipherText(
          textToEncrypt,
          associatedText.getBytes,
          new GCMParameterSpec(96, "hjdfbhvbhvbvjvjfvb".getBytes),
          key
        )
      )

      encryptedAttempt.failureReason should include(
        "Key being used is not valid." +
          " It could be due to invalid encoding, wrong length or uninitialized"
      )
    }

    "return an EncryptionDecryptionError if the algorithm is invalid" in {
      val secureGCMEncryter = new SecureGCMCipher {
        override val ALGORITHM_TO_TRANSFORM_STRING: String = "invalid"
      }
      val encryptedAttempt = intercept[EncryptionDecryptionException](
        secureGCMEncryter.encrypt(textToEncrypt, associatedText, secretKey)
      )

      encryptedAttempt.failureReason should include("Algorithm being requested is not available in this environment")
    }

    "return an EncryptionDecryptionError if the padding is invalid" in {
      val secureGCMEncryter = new SecureGCMCipher {
        override def getCipherInstance(): Cipher = throw new NoSuchPaddingException()
      }
      val encryptedAttempt = intercept[EncryptionDecryptionException](
        secureGCMEncryter.encrypt(textToEncrypt, associatedText, secretKey)
      )

      encryptedAttempt.failureReason should include("Padding Scheme being requested is not available this environment")
    }

    "return an EncryptionDecryptionError if an InvalidAlgorithmParameterException is thrown" in {
      val secureGCMEncryter = new SecureGCMCipher {
        override def getCipherInstance(): Cipher = throw new InvalidAlgorithmParameterException()
      }
      val encryptedAttempt = intercept[EncryptionDecryptionException](
        secureGCMEncryter.encrypt(textToEncrypt, associatedText, secretKey)
      )

      encryptedAttempt.failureReason should include("Algorithm parameters being specified are not valid")
    }

    "return an EncryptionDecryptionError if a IllegalStateException is thrown" in {
      val secureGCMEncryter = new SecureGCMCipher {
        override def getCipherInstance(): Cipher = throw new IllegalStateException()
      }
      val encryptedAttempt = intercept[EncryptionDecryptionException](
        secureGCMEncryter.encrypt(textToEncrypt, associatedText, secretKey)
      )

      encryptedAttempt.failureReason should include("Cipher is in an illegal state")
    }

    "return an EncryptionDecryptionError if a UnsupportedOperationException is thrown" in {
      val secureGCMEncryter = new SecureGCMCipher {
        override def getCipherInstance(): Cipher = throw new UnsupportedOperationException()
      }
      val encryptedAttempt = intercept[EncryptionDecryptionException](
        secureGCMEncryter.encrypt(textToEncrypt, associatedText, secretKey)
      )

      encryptedAttempt.failureReason should include("Provider might not be supporting this method")
    }

    "return an EncryptionDecryptionError if a IllegalBlockSizeException is thrown" in {
      val secureGCMEncryter = new SecureGCMCipher {
        override def getCipherInstance(): Cipher = throw new IllegalBlockSizeException()
      }
      val encryptedAttempt = intercept[EncryptionDecryptionException](
        secureGCMEncryter.encrypt(textToEncrypt, associatedText, secretKey)
      )

      encryptedAttempt.failureReason should include("Error occurred due to block size")
    }

    "return an EncryptionDecryptionError if a RuntimeException is thrown" in {
      val secureGCMEncryter = new SecureGCMCipher {
        override def getCipherInstance(): Cipher = throw new RuntimeException()
      }
      val encryptedAttempt = intercept[EncryptionDecryptionException](
        secureGCMEncryter.encrypt(textToEncrypt, associatedText, secretKey)
      )

      encryptedAttempt.failureReason should include("Unexpected exception")
    }
  }
}
