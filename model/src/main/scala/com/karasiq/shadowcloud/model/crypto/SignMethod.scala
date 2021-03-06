package com.karasiq.shadowcloud.model.crypto

import com.karasiq.shadowcloud.config.SerializedProps

@SerialVersionUID(0L)
final case class SignMethod(algorithm: String, hashingMethod: HashingMethod, keySize: Int = 256,
                            config: SerializedProps = SerializedProps.empty, provider: String = "") extends CryptoMethod {

  @transient
  private[this] lazy val _hashCode = scala.util.hashing.MurmurHash3.productHash(this)

  override def hashCode(): Int = {
    _hashCode
  }

  override def toString: String = {
    if (CryptoMethod.isNoOpMethod(this)) {
      "SignMethod.none"
    } else {
      s"SignMethod(${if (provider.isEmpty) algorithm else provider + ":" + algorithm}, $hashingMethod, $keySize bits${if (config.isEmpty) "" else ", " + config})"
    }
  }
}

object SignMethod {
  val none = SignMethod("", HashingMethod.none)
}
