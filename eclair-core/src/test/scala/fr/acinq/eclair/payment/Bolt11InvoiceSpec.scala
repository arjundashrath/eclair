/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, BtcDouble, ByteVector32, Crypto, MilliBtcDouble, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{PaymentMetadata, PaymentSecret, _}
import fr.acinq.eclair.payment.Bolt11Invoice._
import fr.acinq.eclair.{CltvExpiryDelta, Feature, FeatureScope, FeatureSupport, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TestConstants, TimestampSecond, TimestampSecondLong, ToMilliSatoshiConversion, UnknownFeature, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.DecodeResult
import scodec.bits._
import scodec.codecs.bits

import scala.concurrent.duration.DurationInt
import scala.util.Success

/**
 * Created by fabrice on 15/05/17.
 */

class Bolt11InvoiceSpec extends AnyFunSuite {

  val priv = PrivateKey(hex"e126f68f7eafcc8b74f54d269fe206be715000f94dac067d1c04a8ca3b2db734")
  val pub = priv.publicKey
  val nodeId = pub
  assert(nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))

  // Copy of Bolt11Invoice.apply that doesn't strip unknown features
  def createInvoiceUnsafe(chainHash: ByteVector32,
            amount: Option[MilliSatoshi],
            paymentHash: ByteVector32,
            privateKey: PrivateKey,
            description: Either[String, ByteVector32],
            minFinalCltvExpiryDelta: CltvExpiryDelta,
            fallbackAddress: Option[String] = None,
            expirySeconds: Option[Long] = None,
            extraHops: List[List[ExtraHop]] = Nil,
            timestamp: TimestampSecond = TimestampSecond.now(),
            paymentSecret: ByteVector32 = randomBytes32(),
            paymentMetadata: Option[ByteVector] = None,
            features: Features[FeatureScope] = defaultFeatures.unscoped()): Bolt11Invoice = {
    require(features.hasFeature(Features.PaymentSecret, Some(FeatureSupport.Mandatory)), "invoices must require a payment secret")
    val prefix = prefixes(chainHash)
    val tags = {
      val defaultTags = List(
        Some(PaymentHash(paymentHash)),
        Some(description.fold(Description, DescriptionHash)),
        Some(Bolt11Invoice.PaymentSecret(paymentSecret)),
        paymentMetadata.map(Bolt11Invoice.PaymentMetadata),
        fallbackAddress.map(FallbackAddress(_)),
        expirySeconds.map(Expiry(_)),
        Some(MinFinalCltvExpiry(minFinalCltvExpiryDelta.toInt)),
        Some(InvoiceFeatures(features))
      ).flatten
      val routingInfoTags = extraHops.map(RoutingInfo)
      defaultTags ++ routingInfoTags
    }
    Bolt11Invoice(
      prefix = prefix,
      amount_opt = amount,
      createdAt = timestamp,
      nodeId = privateKey.publicKey,
      tags = tags,
      signature = ByteVector.empty
    ).sign(privateKey)
  }

  test("check minimal unit is used") {
    assert('p' === Amount.unit(1 msat))
    assert('p' === Amount.unit(99 msat))
    assert('n' === Amount.unit(100 msat))
    assert('p' === Amount.unit(101 msat))
    assert('n' === Amount.unit((1 sat).toMilliSatoshi))
    assert('u' === Amount.unit((100 sat).toMilliSatoshi))
    assert('n' === Amount.unit((101 sat).toMilliSatoshi))
    assert('u' === Amount.unit((1155400 sat).toMilliSatoshi))
    assert('m' === Amount.unit((1 millibtc).toMilliSatoshi))
    assert('m' === Amount.unit((10 millibtc).toMilliSatoshi))
    assert('m' === Amount.unit((1 btc).toMilliSatoshi))
  }

  test("decode empty amount") {
    assert(Amount.decode("") === Success(None))
    assert(Amount.decode("0") === Success(None))
    assert(Amount.decode("0p") === Success(None))
    assert(Amount.decode("0n") === Success(None))
    assert(Amount.decode("0u") === Success(None))
    assert(Amount.decode("0m") === Success(None))
  }

  test("check that we can still decode non-minimal amount encoding") {
    assert(Amount.decode("1000u") === Success(Some(100000000 msat)))
    assert(Amount.decode("1000000n") === Success(Some(100000000 msat)))
    assert(Amount.decode("1000000000p") === Success(Some(100000000 msat)))
  }

  test("data string -> bitvector") {
    assert(string2Bits("p") === bin"00001")
    assert(string2Bits("pz") === bin"0000100010")
  }

  test("minimal length long, left-padded to be multiple of 5") {
    assert(long2bits(0) == bin"")
    assert(long2bits(1) == bin"00001")
    assert(long2bits(42) == bin"0000101010")
    assert(long2bits(255) == bin"0011111111")
    assert(long2bits(256) == bin"0100000000")
    assert(long2bits(3600) == bin"000111000010000")
  }

  test("verify that padding is zero") {
    val codec = Bolt11Invoice.Codecs.alignedBytesCodec(bits)
    assert(codec.decode(bin"1010101000").require == DecodeResult(bin"10101010", BitVector.empty))
    assert(codec.decode(bin"1010101001").isFailure) // non-zero padding
  }

  test("Please make a donation of any amount using payment_hash 0001020304050607080900010203040506070809000102030405060708090102 to me @03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad") {
    val ref = "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.isEmpty)
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.paymentSecret.map(_.bytes) === Some(hex"1111111111111111111111111111111111111111111111111111111111111111"))
    assert(invoice.features === Features(Features.VariableLengthOnion -> Mandatory, Features.PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Left("Please consider supporting this project"))
    assert(invoice.fallbackAddress() === None)
    assert(invoice.tags.size === 4)
    assert(invoice.sign(priv).toString == ref)
  }

  test("Please send $3 for a cup of coffee to the same peer, within 1 minute") {
    val ref = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt === Some(250000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(Features.VariableLengthOnion -> Mandatory, Features.PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Left("1 cup coffee"))
    assert(invoice.fallbackAddress() === None)
    assert(invoice.tags.size === 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("Please send 0.0025 BTC for a cup of nonsense (ナンセンス 1杯) to the same peer, within one minute") {
    val ref = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpu9qrsgqhtjpauu9ur7fw2thcl4y9vfvh4m9wlfyz2gem29g5ghe2aak2pm3ps8fdhtceqsaagty2vph7utlgj48u0ged6a337aewvraedendscp573dxr"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt === Some(250000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Left("ナンセンス 1杯"))
    assert(invoice.fallbackAddress() === None)
    assert(invoice.tags.size === 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("Now send $24 for an entire list of things (hashed)") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs9qrsgq7ea976txfraylvgzuxs8kgcw23ezlrszfnh8r6qtfpr6cxga50aj6txm9rxrydzd06dfeawfk6swupvz4erwnyutnjq7x39ymw6j38gp7ynn44"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt === Some(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress() === None)
    assert(invoice.tags.size === 4)
    assert(invoice.sign(priv).toString == ref)
  }

  test("The same, on testnet, with a fallback address mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP") {
    val ref = "lntb20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un989qrsgqdj545axuxtnfemtpwkc45hx9d2ft7x04mt8q7y6t0k2dge9e7h8kpy9p34ytyslj3yu569aalz2xdk8xkd7ltxqld94u8h2esmsmacgpghe9k8"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lntb")
    assert(invoice.amount_opt === Some(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress() === Some("mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, with fallback address 1RustyRX2oai4EYYDpQGWvEL62BBGqN9T with extra routing info to go via nodes 029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255 then 039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzq9qrsgqdfjcdk6w3ak5pca9hwfwfh63zrrz06wwfya0ydlzpgzxkn5xagsqz7x9j4jwe7yj7vaf2k9lqsdk45kts2fd0fkr28am0u4w95tt2nsq76cqw0"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt === Some(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress() === Some("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T"))
    assert(invoice.routingInfo === List(List(
      ExtraHop(PublicKey(hex"029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId("66051x263430x1800"), 1 msat, 20, CltvExpiryDelta(3)),
      ExtraHop(PublicKey(hex"039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId("197637x395016x2314"), 2 msat, 30, CltvExpiryDelta(4))
    )))
    assert(invoice.tags.size == 6)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, with fallback (p2sh) address 3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppj3a24vwu6r8ejrss3axul8rxldph2q7z99qrsgqz6qsgww34xlatfj6e3sngrwfy3ytkt29d2qttr8qz2mnedfqysuqypgqex4haa2h8fx3wnypranf3pdwyluftwe680jjcfp438u82xqphf75ym"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt === Some(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress() === Some("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, with fallback (p2wpkh) address bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppqw508d6qejxtdg4y5r3zarvary0c5xw7k9qrsgqt29a0wturnys2hhxpner2e3plp6jyj8qx7548zr2z7ptgjjc7hljm98xhjym0dg52sdrvqamxdezkmqg4gdrvwwnf0kv2jdfnl4xatsqmrnsse"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt === Some(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress() === Some("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q9qrsgq9vlvyj8cqvq6ggvpwd53jncp9nwc47xlrsnenq2zp70fq83qlgesn4u3uyf4tesfkkwwfg3qs54qe426hp3tz7z6sweqdjg05axsrjqp9yrrwc"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt === Some(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress() === Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3 and a minimum htlc cltv expiry of 12") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygscqpvpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q9qrsgq999fraffdzl6c8j7qd325dfurcq7vl0mfkdpdvve9fy3hy4lw0x9j3zcj2qdh5e5pyrp6cncvmxrhchgey64culwmjtw9wym74xm6xqqevh9r0"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt === Some(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress() === Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(invoice.minFinalCltvExpiryDelta === CltvExpiryDelta(12))
    assert(invoice.tags.size == 6)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, please send $30 for coffee beans to the same peer, which supports features 8, 14 and 99, using secret 0x1111111111111111111111111111111111111111111111111111111111111111") {
    val refs = Seq(
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2a25dxl5hrntdtn6zvydt7d66hyzsyhqs4wdynavys42xgl6sgx9c4g7me86a27t07mdtfry458rtjr0v92cnmswpsjscgt2vcse3sgpz3uapa",
      // All upper-case
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2a25dxl5hrntdtn6zvydt7d66hyzsyhqs4wdynavys42xgl6sgx9c4g7me86a27t07mdtfry458rtjr0v92cnmswpsjscgt2vcse3sgpz3uapa".toUpperCase,
      // With ignored fields
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2qrqqqfppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhpnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqspnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnp5qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnpkqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqz599y53s3ujmcfjp5xrdap68qxymkqphwsexhmhr8wdz5usdzkzrse33chw6dlp3jhuhge9ley7j2ayx36kawe7kmgg8sv5ugdyusdcqzn8z9x"
    )

    for (ref <- refs) {
      val invoice = Bolt11Invoice.fromString(ref)
      assert(invoice.prefix === "lnbc")
      assert(invoice.amount_opt === Some(2500000000L msat))
      assert(invoice.paymentHash.bytes === hex"0001020304050607080900010203040506070809000102030405060708090102")
      assert(invoice.paymentSecret === Some(ByteVector32(hex"1111111111111111111111111111111111111111111111111111111111111111")))
      assert(invoice.createdAt === TimestampSecond(1496314658L))
      assert(invoice.nodeId === PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
      assert(invoice.description === Left("coffee beans"))
      assert(features2bits(invoice.features) === bin"1000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000100000000")
      assert(!invoice.features.hasFeature(BasicMultiPartPayment))
      assert(invoice.features.hasFeature(PaymentSecret, Some(Mandatory)))
      assert(!invoice.features.hasFeature(TrampolinePayment))
      assert(TestConstants.Alice.nodeParams.features.invoiceFeatures().areSupported(invoice.features))
      assert(invoice.sign(priv).toString === ref.toLowerCase)
    }
  }

  test("On mainnet, please send $30 for coffee beans to the same peer, which supports features 8, 14, 99 and 100, using secret 0x1111111111111111111111111111111111111111111111111111111111111111") {
    val ref = "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q4psqqqqqqqqqqqqqqqqsgqtqyx5vggfcsll4wu246hz02kp85x4katwsk9639we5n5yngc3yhqkm35jnjw4len8vrnqnf5ejh0mzj9n3vz2px97evektfm2l6wqccp3y7372"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix === "lnbc")
    assert(invoice.amount_opt === Some(2500000000L msat))
    assert(invoice.paymentHash.bytes === hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.paymentSecret === Some(ByteVector32(hex"1111111111111111111111111111111111111111111111111111111111111111")))
    assert(invoice.createdAt === TimestampSecond(1496314658L))
    assert(invoice.nodeId === PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description === Left("coffee beans"))
    assert(invoice.fallbackAddress().isEmpty)
    assert(features2bits(invoice.features) === bin"000011000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000100000000")
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))
    assert(invoice.features.hasFeature(PaymentSecret, Some(Mandatory)))
    assert(!invoice.features.hasFeature(TrampolinePayment))
    assert(!TestConstants.Alice.nodeParams.features.invoiceFeatures().areSupported(invoice.features))
    assert(invoice.sign(priv).toString === ref)
  }

  test("On mainnet, please send 0.00967878534 BTC for a list of items within one week, amount in pico-BTC") {
    val ref = "lnbc9678785340p1pwmna7lpp5gc3xfm08u9qy06djf8dfflhugl6p7lgza6dsjxq454gxhj9t7a0sd8dgfkx7cmtwd68yetpd5s9xar0wfjn5gpc8qhrsdfq24f5ggrxdaezqsnvda3kkum5wfjkzmfqf3jkgem9wgsyuctwdus9xgrcyqcjcgpzgfskx6eqf9hzqnteypzxz7fzypfhg6trddjhygrcyqezcgpzfysywmm5ypxxjemgw3hxjmn8yptk7untd9hxwg3q2d6xjcmtv4ezq7pqxgsxzmnyyqcjqmt0wfjjq6t5v4khxsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygsxqyjw5qcqp2rzjq0gxwkzc8w6323m55m4jyxcjwmy7stt9hwkwe2qxmy8zpsgg7jcuwz87fcqqeuqqqyqqqqlgqqqqn3qq9q9qrsgqrvgkpnmps664wgkp43l22qsgdw4ve24aca4nymnxddlnp8vh9v2sdxlu5ywdxefsfvm0fq3sesf08uf6q9a2ke0hc9j6z6wlxg5z5kqpu2v9wz"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix === "lnbc")
    assert(invoice.amount_opt === Some(967878534 msat))
    assert(invoice.paymentHash.bytes === hex"462264ede7e14047e9b249da94fefc47f41f7d02ee9b091815a5506bc8abf75f")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(TestConstants.Alice.nodeParams.features.invoiceFeatures().areSupported(invoice.features))
    assert(invoice.createdAt === TimestampSecond(1572468703L))
    assert(invoice.nodeId === PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description === Left("Blockstream Store: 88.85 USD for Blockstream Ledger Nano S x 1, \"Back In My Day\" Sticker x 2, \"I Got Lightning Working\" Sticker x 2 and 1 more items"))
    assert(invoice.fallbackAddress().isEmpty)
    assert(invoice.relativeExpiry === 604800.seconds)
    assert(invoice.minFinalCltvExpiryDelta === CltvExpiryDelta(10))
    assert(invoice.routingInfo === Seq(Seq(ExtraHop(PublicKey(hex"03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"), ShortChannelId("589390x3312x1"), 1000 msat, 2500, CltvExpiryDelta(40)))))
    assert(invoice.sign(priv).toString === ref)
  }

  test("On mainnet, please send 0.01 BTC with payment metadata 0x01fafaf0") {
    val ref = "lnbc10m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdp9wpshjmt9de6zqmt9w3skgct5vysxjmnnd9jx2mq8q8a04uqsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q2gqqqqqqsgq7hf8he7ecf7n4ffphs6awl9t6676rrclv9ckg3d3ncn7fct63p6s365duk5wrk202cfy3aj5xnnp5gs3vrdvruverwwq7yzhkf5a3xqpd05wjc"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt === Some(1000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, PaymentMetadata -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.paymentSecret === Some(ByteVector32(hex"1111111111111111111111111111111111111111111111111111111111111111")))
    assert(invoice.description == Left("payment metadata inside"))
    assert(invoice.paymentMetadata === Some(hex"01fafaf0"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("reject invalid invoices") {
    val refs = Seq(
      // Bech32 checksum is invalid.
      "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrnt",
      // Malformed bech32 string (no 1).
      "pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrny",
      // Malformed bech32 string (mixed case).
      "LNBC2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrny",
      // Signature is not recoverable.
      "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgqwgt7mcn5yqw3yx0w94pswkpq6j9uh6xfqqqtsk4tnarugeektd4hg5975x9am52rz4qskukxdmjemg92vvqz8nvmsye63r5ykel43pgz7zq0g2",
      // String is too short.
      "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6na6hlh",
      // Invalid multiplier.
      "lnbc2500x1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgqrrzc4cvfue4zp3hggxp47ag7xnrlr8vgcmkjxk3j5jqethnumgkpqp23z9jclu3v0a7e0aruz366e9wqdykw6dxhdzcjjhldxq0w6wgqcnu43j",
      // Invalid sub-millisatoshi precision.
      "lnbc2500000001p1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgq0lzc236j96a95uv0m3umg28gclm5lqxtqqwk32uuk4k6673k6n5kfvx3d2h8s295fad45fdhmusm8sjudfhlf6dcsxmfvkeywmjdkxcp99202x"
    )
    for (ref <- refs) {
      assertThrows[Exception](Bolt11Invoice.fromString(ref))
    }
  }

  test("correctly serialize/deserialize variable-length tagged fields") {
    val number = 123456
    val codec = Bolt11Invoice.Codecs.dataCodec(scodec.codecs.bits).as[Bolt11Invoice.Expiry]
    val field = Bolt11Invoice.Expiry(number)
    assert(field.toLong == number)

    val serializedExpiry = codec.encode(field).require
    val field1 = codec.decodeValue(serializedExpiry).require
    assert(field1 == field)

    val invoice = Bolt11Invoice(chainHash = Block.LivenetGenesisBlock.hash, amount = Some(123 msat), paymentHash = ByteVector32(ByteVector.fill(32)(1)), privateKey = priv, description = Left("Some invoice"), minFinalCltvExpiryDelta = CltvExpiryDelta(18), expirySeconds = Some(123456), timestamp = 12345 unixsec)
    assert(invoice.minFinalCltvExpiryDelta === CltvExpiryDelta(18))
    val serialized = invoice.toString
    val pr1 = Bolt11Invoice.fromString(serialized)
    assert(invoice == pr1)
  }

  test("ignore unknown tags") {
    val invoice = Bolt11Invoice(
      prefix = "lntb",
      amount_opt = Some(100000 msat),
      createdAt = TimestampSecond.now(),
      nodeId = nodeId,
      tags = List(
        PaymentHash(ByteVector32(ByteVector.fill(32)(1))),
        Description("description"),
        UnknownTag21(BitVector("some data we don't understand".getBytes))
      ),
      signature = ByteVector.empty).sign(priv)

    val serialized = invoice.toString
    val pr1 = Bolt11Invoice.fromString(serialized)
    val Some(_) = pr1.tags.collectFirst { case u: UnknownTag21 => u }
  }

  test("ignore hash tags with invalid length") {
    // Bolt11: A reader: MUST skip over p, h, s or n fields that do NOT have data_lengths of 52, 52, 52 or 53, respectively.
    def bits(i: Int) = BitVector.fill(i * 5)(high = false)

    val inputs = Map(
      "ppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag1(bits(51)),
      "pp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag1(bits(53)),
      "hpnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag23(bits(51)),
      "hp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag23(bits(53)),
      "spnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag16(bits(51)),
      "sp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag16(bits(53)),
      "np5qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> UnknownTag19(bits(52)),
      "npkqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> UnknownTag19(bits(54))
    )

    for ((input, value) <- inputs) {
      val data = string2Bits(input)
      val decoded = Codecs.taggedFieldCodec.decode(data).require.value
      assert(decoded === value)
      val encoded = Codecs.taggedFieldCodec.encode(value).require
      assert(encoded === data)
    }
  }

  test("accept uppercase invoices") {
    val input = "lntb1500n1pwxx94fsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5q3xzmwuvxpkyhz6pvg3fcfxz0259kgh367qazj62af9rs0pw07dsdpa2fjkzep6yp58garswvaz7tmvd9nksarwd9hxw6n0w4kx2tnrdakj7grfwvs8wcqzysxqr23swwl9egjej7rvvt9zdxrtpy8xuu6cckdwajfccmtz7n90ea34k3j595w77pt69s5dx5a46f4k4w5avtvjkc4l4rm8n4xmk7fe3pms3pspdd032j"
    assert(Bolt11Invoice.fromString(input.toUpperCase()).toString == input)
  }

  test("Pay 1 BTC without multiplier") {
    val ref = "lnbc1000m1pdkmqhusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5n2ees808r98m0rh4472yyth0c5fptzcxmexcjznrzmq8xald0cgqdqsf4ujqarfwqsxymmccqp2pv37ezvhth477nu0yhhjlcry372eef57qmldhreqnr0kx82jkupp3n7nw42u3kdyyjskdr8jhjy2vugr3skdmy8ersft36969xplkxsp2v7c58"
    val invoice = Bolt11Invoice.fromString(ref)
    assert(invoice.amount_opt === Some(100000000000L msat))
    assert(features2bits(invoice.features) === BitVector.empty)
  }

  test("supported invoice features") {
    val nodeParams = TestConstants.Alice.nodeParams.copy(features = Features(knownFeatures.map(f => f -> Optional).toMap))
    case class Result(allowMultiPart: Boolean, requirePaymentSecret: Boolean, areSupported: Boolean) // "supported" is based on the "it's okay to be odd" rule
    val featureBits = Map(
      Features(bin"               00000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00010100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00100100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00010100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00010100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00100100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               01000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0000010000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0000011000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0000110000101000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0000100000101000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0010000000101000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      // those are useful for nonreg testing of the areSupported method (which needs to be updated with every new supported mandatory bit)
      Features(bin"     000001000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = false),
      Features(bin"     000100000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"00000010000000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = false),
      Features(bin"00001000000000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = false)
    )

    for ((features, res) <- featureBits) {
      val invoice = createInvoiceUnsafe(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = features)
      assert(Result(invoice.features.hasFeature(BasicMultiPartPayment), invoice.features.hasFeature(PaymentSecret, Some(Mandatory)), nodeParams.features.invoiceFeatures().areSupported(invoice.features)) === res)
      assert(Bolt11Invoice.fromString(invoice.toString) === invoice)
    }
  }

  test("feature bits to minimally-encoded feature bytes") {
    val testCases = Seq(
      (bin"   0010000100000101", hex"  2105"),
      (bin"   1010000100000101", hex"  a105"),
      (bin"  11000000000000110", hex"018006"),
      (bin"  01000000000000110", hex"  8006"),
      (bin" 001000000000000000", hex"  8000"),
      (bin" 101000000000000000", hex"028000"),
      (bin"0101010000000000110", hex"02a006"),
      (bin"1000110000000000110", hex"046006")
    )

    for ((bitmask, featureBytes) <- testCases) {
      assert(Features(bitmask).toByteVector === featureBytes)
    }
  }

  test("payment secret") {
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18))
    assert(invoice.paymentSecret.isDefined)
    assert(invoice.features === Features(PaymentSecret -> Mandatory, VariableLengthOnion -> Mandatory))
    assert(invoice.features.hasFeature(PaymentSecret, Some(Mandatory)))

    val pr1 = Bolt11Invoice.fromString(invoice.toString)
    assert(pr1.paymentSecret === invoice.paymentSecret)

    val pr2 = Bolt11Invoice.fromString("lnbc40n1pw9qjvwpp5qq3w2ln6krepcslqszkrsfzwy49y0407hvks30ec6pu9s07jur3sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdencqzysxqrrss7ju0s4dwx6w8a95a9p2xc5vudl09gjl0w2n02sjrvffde632nxwh2l4w35nqepj4j5njhh4z65wyfc724yj6dn9wajvajfn5j7em6wsq2elakl")
    assert(!pr2.features.hasFeature(PaymentSecret, Some(Mandatory)))
    assert(pr2.paymentSecret === None)

    // An invoice that sets the payment secret feature bit must provide a payment secret.
    assertThrows[IllegalArgumentException](
      Bolt11Invoice.fromString("lnbc1230p1pwljzn3pp5qyqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdq52dhk6efqd9h8vmmfvdjs9qypqsqylvwhf7xlpy6xpecsnpcjjuuslmzzgeyv90mh7k7vs88k2dkxgrkt75qyfjv5ckygw206re7spga5zfd4agtdvtktxh5pkjzhn9dq2cqz9upw7")
    )

    // A multi-part invoice must use a payment secret.
    assertThrows[IllegalArgumentException](
      Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("MPP without secrets"), CltvExpiryDelta(18), features = Features(VariableLengthOnion -> Optional, PaymentSecret -> Optional))
    )
  }

  test("trampoline") {
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18))
    assert(!invoice.features.hasFeature(TrampolinePayment))

    val pr1 = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, TrampolinePayment -> Optional))
    assert(!pr1.features.hasFeature(BasicMultiPartPayment))
    assert(pr1.features.hasFeature(TrampolinePayment))

    val pr2 = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, TrampolinePayment -> Optional))
    assert(pr2.features.hasFeature(BasicMultiPartPayment))
    assert(pr2.features.hasFeature(TrampolinePayment))

    val pr3 = Bolt11Invoice.fromString("lnbc40n1pw9qjvwpp5qq3w2ln6krepcslqszkrsfzwy49y0407hvks30ec6pu9s07jur3sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdencqzysxqrrss7ju0s4dwx6w8a95a9p2xc5vudl09gjl0w2n02sjrvffde632nxwh2l4w35nqepj4j5njhh4z65wyfc724yj6dn9wajvajfn5j7em6wsq2elakl")
    assert(!pr3.features.hasFeature(TrampolinePayment))
  }

  test("nonreg") {
    val requests = List(
      "lnbc40n1pw9qjvwpp5qq3w2ln6krepcslqszkrsfzwy49y0407hvks30ec6pu9s07jur3sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdencqzysxqrrss7ju0s4dwx6w8a95a9p2xc5vudl09gjl0w2n02sjrvffde632nxwh2l4w35nqepj4j5njhh4z65wyfc724yj6dn9wajvajfn5j7em6wsq2elakl" -> PublicKey(hex"02cda8c01b2303e91bec74c43093d5f1c4fd42a95671ae27bf853d7dfea9b78c06"),
      "lnbc1500n1pwyvqwfpp5p5nxwpuk02nd2xtzwex97gtjlpdv0lxj5z08vdd0hes7a0h437qsdpa2fjkzep6yp8kumrfdejjqempd43xc6twvusxjueqd9kxcet8v9kzqct8v95kucqzysxqr23s8r9seqv6datylwtjcvlpdkukfep7g80hujz3w8t599saae7gap6j48gs97z4fvrx4t4ajra6pvdyf5ledw3tg7h2s3606qm79kk59zqpeygdhd" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc800n1pwykdmfpp5zqjae54l4ecmvm9v338vw2n07q2ehywvy4pvay53s7068t8yjvhqdqddpjkcmr0yysjzcqp27lya2lz7d80uxt6vevcwzy32227j3nsgyqlrxuwgs22u6728ldszlc70qgcs56wglrutn8jnnnelsk38d6yaqccmw8kmmdlfsyjd20qp69knex" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc300n1pwzezrnpp5zgwqadf4zjygmhf3xms8m4dd8f4mdq26unr5mfxuyzgqcgc049tqdq9dpjhjcqp23gxhs2rawqxdvr7f7lmj46tdvkncnsz8q5jp2kge8ndfm4dpevxrg5xj4ufp36x89gmaw04lgpap7e3x9jcjydwhcj9l84wmts2lg6qquvpque" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc10n1pdm2qaxpp5zlyxcc5dypurzyjamt6kk6a8rpad7je5r4w8fj79u6fktnqu085sdpl2pshjmt9de6zqen0wgsrzgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp2e3nq4xh20prn9kx8etqgjjekzzjhep27mnqtyy62makh4gqc4akrzhe3nmj8lnwtd40ne5gn8myruvrt9p6vpuwmc4ghk7587erwqncpx9sds0" -> PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca"),
      "lnbc800n1pwp5uuhpp5y8aarm9j9x9cer0gah9ymfkcqq4j4rn3zr7y9xhsgar5pmaceaqqdqdvf5hgcm0d9hzzcqp2vf8ramzsgdznxd5yxhrxffuk43pst9ng7cqcez9p2zvykpcf039rp9vutpe6wfds744yr73ztyps2z58nkmflye9yt4v3d0qz8z3d9qqq3kv54" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc1500n1pdl686hpp5y7mz3lgvrfccqnk9es6trumjgqdpjwcecycpkdggnx7h6cuup90sdpa2fjkzep6ypqkymm4wssycnjzf9rjqurjda4x2cm5ypskuepqv93x7at5ypek7cqzysxqr23s5e864m06fcfp3axsefy276d77tzp0xzzzdfl6p46wvstkeqhu50khm9yxea2d9efp7lvthrta0ktmhsv52hf3tvxm0unsauhmfmp27cqqx4xxe" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc80n1pwykw99pp5965lyj4uesussdrk0lfyd2qss9m23yjdjkpmhw0975zky2xlhdtsdpl2pshjmt9de6zqen0wgsrsgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp27677yc44l22jxexewew7lzka7g5864gdpr6y5v6s6tqmn8xztltk9qnna2qwrsm7gfyrpqvhaz4u3egcalpx2gxef3kvqwd44hekfxcqr7nwhf" -> PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca"),
      "lnbc2200n1pwp4pwnpp5xy5f5kl83ytwuz0sgyypmqaqtjs68s3hrwgnnt445tqv7stu5kyqdpyvf5hgcm0d9hzqmn0wssxymr0vd4kx6rpd9hqcqp25y9w3wc3ztxhemsqch640g4u00szvvfk4vxr7klsakvn8cjcunjq8rwejzy6cfwj90ulycahnq43lff8m84xqf3tusslq2w69htwwlcpfqskmc" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc300n1pwp50ggpp5x7x5a9zs26amr7rqngp2sjee2c3qc80ztvex00zxn7xkuzuhjkxqdq9dpjhjcqp2s464vnrpx7aynh26vsxx6s3m52x88dqen56pzxxnxmc9s7y5v0dprsdlv5q430zy33lcl5ll6uy60m7c9yrkjl8yxz7lgsqky3ka57qq4qeyz3" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc10n1pd6jt93pp58vtzf4gup4vvqyknfakvh59avaek22hd0026snvpdnc846ypqrdsdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnqvscqzysxqyd9uq3sv9xkv2sgdf2nuvs97d2wkzj5g75rljnh5wy5wqhnauvqhxd9fpq898emtz8hul8cnxmc9wtj2777ehgnnyhcrs0y5zuhy8rs0jv6cqqe24tw" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc890n1pwzu4uqpp5gy274lq0m5hzxuxy90vf65wchdszrazz9zxjdk30ed05kyjvwxrqdzq2pshjmt9de6zqen0wgsrswfqwp5hsetvwvsxzapqwdshgmmndp5hxtnsd3skxefwxqzjccqp2qjvlfyl4rmc56gerx70lxcrjjlnrjfz677ezw4lwzy6syqh4rnlql6t6n3pdfxkcal9jp98plgf2zqzz8jxfza9vjw3vd4t62ws8gkgqhv9x28" -> PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca"),
      "lnbc79760n1pd7cwyapp5gevl4mv968fs4le3tytzhr9r8tdk8cu3q7kfx348ut7xyntvnvmsdz92pskjepqw3hjqmrfva58gmnfdenjqumvda6zqmtpvd5xjmn9ypnx7u3qx5czq5msd9h8xcqzysxqrrssjzky68fdnhvee7aw089d5zltahfhy2ffa96pwf7fszjnm6mv0fzpv88jwaenm5qfg64pl768q8hf2vnvc5xsrpqd45nca2mewsv55wcpmhskah" -> PublicKey(hex"039f01ad62e5208940faff11d0bbc997582eafad7642aaf53de6a5f6551ab73400"),
      "lnbc90n1pduns5qpp5f5h5ghga4cp7uj9de35ksk00a2ed9jf774zy7va37k5zet5cds8sdpl2pshjmt9de6zqen0wgsrjgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp28ynysm3clcq865y9umys8t2f54anlsu2wfpyfxgq09ht3qfez9x9z9fpff8wzqwzua2t9vayzm4ek3vf4k4s5cdg3a6hp9vsgg9klpgpmafvnv" -> PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca"),
      "lnbc10u1pw9nehppp5tf0cpc3nx3wpk6j2n9teqwd8kuvryh69hv65w7p5u9cqhse3nmgsdzz2p6hycmgv9ek2gr0vcsrzgrxd3hhwetjyphkugzzd96xxmmfdcsywunpwejhjctjvscqp222vxxwq70temepf6n0xlzk0asr43ppqrt0mf6eclnfd5mxf6uhv5wvsqgdvht6uqxfw2vgdku5gfyhgguepvnjfu7s4kuthtnuxy0hsq6wwv9d" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc30n1pw9qjwmpp5tcdc9wcr0avr5q96jlez09eax7djwmc475d5cylezsd652zvptjsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdf4cqzysxqrrss7r8gn9d6klf2urzdjrq3x67a4u25wpeju5utusnc539aj5462y7kv9w56mndcx8jad7aa7qz8f8qpdw9qlx52feyemwd7afqxu45jxsqyzwns9" -> PublicKey(hex"02cda8c01b2303e91bec74c43093d5f1c4fd42a95671ae27bf853d7dfea9b78c06"),
      "lnbc10u1pw9x36xpp5tlk00k0dftfx9vh40mtdlu844c9v65ad0kslnrvyuzfxqhdur46qdzz2p6hycmgv9ek2gr0vcsrzgrxd3hhwetjyphkugzzd96xxmmfdcsywunpwejhjctjvscqp2fpudmf4tt0crardf0k7vk5qs4mvys88el6e7pg62hgdt9t6ckf48l6jh4ckp87zpcnal6xnu33hxdd8k27vq2702688ww04kc065r7cqw3cqs3" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc40n1pd6jttkpp5v8p97ezd3uz4ruw4w8w0gt4yr3ajtrmaeqe23ttxvpuh0cy79axqdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnqvscqzysxqyd9uq3r88ajpz77z6lg4wc7srhsk7m26guuvhdlpea6889m9jnc9a25sx7rdtryjukew86mtcngl6d8zqh9trtu60cmmwfx6845q08z06p6qpl3l55t" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc1pwr7fqhpp5vhur3ahtumqz5mkramxr22597gaa9rnrjch8gxwr9h7r56umsjpqdpl235hqurfdcs9xct5daeks6tngask6etnyq58g6tswp5kutndv55jsaf3x5unj2gcqzysxqyz5vq88jysqvrwhq6qe38jdulefx0z9j7sfw85wqc6athfx9h77fjnjxjvprz76ayna0rcjllgu5ka960rul3qxvsrr9zth5plaerq96ursgpsshuee" -> PublicKey(hex"03c2abfa93eacec04721c019644584424aab2ba4dff3ac9bdab4e9c97007491dda"),
      "lnbc10n1pw9rt5hpp5dsv5ux7xlmhmrpqnffgj6nf03mvx5zpns3578k2c5my3znnhz0gqdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwwp3cqzysxqrrssnrasvcr5ydng283zdfpw38qtqfxjnzhdmdx9wly9dsqmsxvksrkzkqrcenu6h36g4g55q56ejk429nm4zjfgssh8uhs7gs760z63ggcqp3gyd6" -> PublicKey(hex"02cda8c01b2303e91bec74c43093d5f1c4fd42a95671ae27bf853d7dfea9b78c06"),
      "lnbc1500n1pd7u7p4pp5d54vffcehkcy79gm0fkqrthh3y576jy9flzpy9rf6syua0s5p0jqdpa2fjkzep6ypxhjgz90pcx2unfv4hxxefqdanzqargv5s9xetrdahxggzvd9nkscqzysxqr23sklptztnk25aqzwty35gk9q7jtfzjywdfx23d8a37g2eaejrv3d9nnt87m98s4eps87q87pzfd6hkd077emjupe0pcazpt9kaphehufqqu7k37h" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc10n1pdunsmgpp5wn90mffjvkd06pe84lpa6e370024wwv7xfw0tdxlt6qq8hc7d7rqdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcngvscqzysxqyd9uqs0cqtrum6h7dct88nkjxwxvte7hjh9pusx64tp35u0m6qhqy5dgn9j27fs37mg0w3ruf7enxlsc9xmlasgjzyyaaxqdxu9x5w0md4fspgz8twv" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc700n1pwp50wapp5w7eearwr7qjhz5vk5zq4g0t75f90mrekwnw4e795qfjxyaq27dxsdqvdp6kuar9wgeqcqp20gfw78vvasjm45l6zfxmfwn59ac9dukp36mf0y3gpquhp7rptddxy7d32ptmqukeghvamlkmve9n94sxmxglun4zwtkyhk43e6lw8qspc9y9ww" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc10n1pd6jvy5pp50x9lymptter9najcdpgrcnqn34wq34f49vmnllc57ezyvtlg8ayqdpdtfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yq6rvcqzysxqyd9uqcejk56vfz3y80u3npefpx82f0tghua88a8x2d33gmxcjm45q6l5xwurwyp9aj2p59cr0lknpk0eujfdax32v4px4m22u6zr5z40zxvqp5m85cr" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc10n1pw9pqz7pp50782e2u9s25gqacx7mvnuhg3xxwumum89dymdq3vlsrsmaeeqsxsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwd3ccqzysxqrrsstxqhw2kvdfwsf7c27aaae45fheq9rzndesu4mph9dq08sawa0auz7e0z7jn9qf3zphegv2ermup0fgce0phqmf73j4zx88v3ksrgeeqq9yzzad" -> PublicKey(hex"02cda8c01b2303e91bec74c43093d5f1c4fd42a95671ae27bf853d7dfea9b78c06"),
      "lnbc1300n1pwq4fx7pp5sqmq97yfxhhk7xv7u8cuc8jgv5drse45f5pmtx6f5ng2cqm332uqdq4e2279q9zux62tc5q5t9fgcqp29a662u3p2h4h4ucdav4xrlxz2rtwvvtward7htsrldpsc5erknkyxu0x2xt9qv0u766jadeetsz9pj4rljpjy0g8ayqqt2q8esewsrqpc8v4nw" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc1u1pd7u7tnpp5s9he3ccpsmfdkzrsjns7p3wpz7veen6xxwxdca3khwqyh2ezk8kqdqdg9jxgg8sn7f27cqzysxqr23ssm4krdc4s0zqhfk97n0aclxsmaga208pa8c0hz3zyauqsjjxfj7kw6t29dkucp68s8s4zfdgp97kkmzgy25yuj0dcec85d9c50sgjqgq5jhl4e" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc1200n1pwq5kf2pp5snkm9kr0slgzfc806k4c8q93d4y57q3lz745v2hefx952rhuymrqdq509shjgrzd96xxmmfdcsscqp2w5ta9uwzhmxxp0mnhwwvnjdn6ev4huj3tha5d80ajv2p5phe8wk32yn7ch6lennx4zzawqtd34aqetataxjmrz39gzjl256walhw03gpxz79rr" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc1500n1pd7u7v0pp5s6d0wqexag3aqzugaw3gs7hw7a2wrq6l8fh9s42ndqu8zu480m0sdqvg9jxgg8zn2sscqzysxqr23sm23myatjdsp3003rlasgzwg3rlr0ca8uqdt5d79lxmdwqptufr89r5rgk4np4ag0kcw7at6s6eqdany0k6m0ezjva0cyda5arpaw7lcqgzjl7u" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc100n1pd6jv8ypp53p6fdd954h3ffmyj6av4nzcnwfuyvn9rrsc2u6y22xnfs0l0cssqdpdtfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqerscqzysxqyd9uqyefde4la0qmglafzv8q34wqsf4mtwd8ausufavkp2e7paewd3mqsg0gsdmvrknw80t92cuvu9raevrnxtpsye0utklhpunsz68a9veqpkypx9j" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc2300n1pwp50w8pp53030gw8rsqac6f3sqqa9exxwfvphsl4v4w484eynspwgv5v6vyrsdp9w35xjueqd9ejqmn0wssx67fqwpshxumhdaexgcqp2zmspcx992fvezxqkyf3rkcxc9dm2vr4ewfx42c0fccg4ea72fyd3pd6vn94tfy9t39y0hg0hupak2nv0n6pzy8culeceq8kzpwjy0tsp4fwqw5" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc10n1pwykdlhpp53392ama65h3lnc4w55yqycp9v2ackexugl0ahz4jyc7fqtyuk85qdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwvejcqzysxqrrsszkwrx54an8lhr9h4h3d7lgpjrd370zucx0fdusaklqh2xgytr8hhgq5u0kvs56l8j53uktlmz3mqhhmn88kwwxfksnham9p6ws5pwxsqnpzyda" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc10470n1pw9qf40pp535pels2faqwau2rmqkgzn0rgtsu9u6qaxe5y6ttgjx5qm4pg0kgsdzy2pshjmt9de6zqen0wgsrzvp5xus8q6tcv4k8xgrpwss8xct5daeks6tn9ecxcctrv5hqxqzjccqp27sp3m204a7d47at5jkkewa7rvewdmpwaqh2ss72cajafyf7dts9ne67hw9pps2ud69p4fw95y9cdk35aef43cv35s0zzj37qu7s395cp2vw5mu" -> PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca"),
      "lnbc100n1pwytlgspp5365rx7ell807x5jsr7ykf2k7p5z77qvxjx8x6pfhh5298xnr6d2sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwvpscqzysxqrrssh9mphycg7e9lr58c267yerlcd9ka8lrljm8ygpnwu2v63jm7ax48y7qal25qy0ewpxw39r5whnqh93zw97gnnw64ss97n69975wh9gsqj7vudu" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc210n1pdunsefpp5jxn3hlj86evlwgsz5d70hquy78k28ahdwjmlagx6qly9x29pu4uqdzq2pshjmt9de6zqen0wgsryvfqwp5hsetvwvsxzapqwdshgmmndp5hxtnsd3skxefwxqzjccqp2snr8trjcrr5xyy7g63uq7mewqyp9k3d0duznw23zhynaz6pj3uwk48yffqn8p0jugv2z03dxquc8azuwr8myjgwzh69a34fl2lnmq2sppac733" -> PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca"),
      "lnbc1700n1pwr7z98pp5j5r5q5c7syavjjz7czjvng4y95w0rd8zkl7q43sm7spg9ht2sjfqdquwf6kumnfdenjqmrfva58gmnfdenscqp2jrhlc758m734gw5td4gchcn9j5cp5p38zj3tcpvgkegxewat38d3h24kn0c2ac2pleuqp5dutvw5fmk4d2v3trcqhl5pdxqq8swnldcqtq0akh" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc1500n1pdl05k5pp5nyd9netjpzn27slyj2np4slpmlz8dy69q7hygwm8ff4mey2jee5sdpa2fjkzep6ypxhjgz90pcx2unfv4hxxefqdanzqargv5s9xetrdahxggzvd9nkscqzysxqr23sqdd8t97qjc77pqa7jv7umc499jqkk0kwchapswj3xrukndr7g2nqna5x87n49uynty4pxexkt3fslyle7mwz708rs0rnnn44dnav9mgplf0aj7" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc1u1pwyvxrppp5nvm98wnqdee838wtfmhfjx9s49eduzu3rx0fqec2wenadth8pxqsdqdg9jxgg8sn7vgycqzysxqr23snuza3t8x0tvusu07epal9rqxh4cq22m64amuzd6x607s0w55a5xpefp2xlxmej9r6nktmwv5td3849y2sg7pckwk9r8vqqps8g4u66qq85mp3g" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc10n1pw9qjwppp55nx7xw3sytnfle67mh70dyukr4g4chyfmp4x4ag2hgjcts4kydnsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwd3ccqzysxqrrss7t24v6w7dwtd65g64qcz77clgye7n8l0j67qh32q4jrw9d2dk2444vma7j6nedgx2ywel3e9ns4r257zprsn7t5uca045xxudz9pqzsqfena6v" -> PublicKey(hex"02cda8c01b2303e91bec74c43093d5f1c4fd42a95671ae27bf853d7dfea9b78c06"),
      "lnbc10u1pw9x373pp549mpcznu3q0r4ml095kjg38pvsdptzja8vhpyvc2avatc2cegycsdzz2p6hycmgv9ek2gr0vcsrzgrxd3hhwetjyphkugzzd96xxmmfdcsywunpwejhjctjvscqp2tgqwhzyjmpfymrshnaw6rwmy4rgrtjmmp66dr9v54xp52rsyzqd5htc3lu3k52t06fqk8yj05nsw0nnssak3ywev4n3xs3jgz42urmspjeqyw0" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc1500n1pd7u7vupp54jm8s8lmgnnru0ndwpxhm5qwllkrarasr9fy9zkunf49ct8mw9ssdqvg9jxgg8zn2sscqzysxqr23s4njradkzzaswlsgs0a6zc3cd28xc08t5car0k7su6q3u3vjvqt6xq2kpaadgt5x9suxx50rkevfw563fupzqzpc9m6dqsjcr8qt6k2sqelr838" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc720n1pwypj4epp5k2saqsjznpvevsm9mzqfan3d9fz967x5lp39g3nwsxdkusps73csdzq2pshjmt9de6zqen0wgsrwv3qwp5hsetvwvsxzapqwdshgmmndp5hxtnsd3skxefwxqzjccqp2d3ltxtq0r795emmp7yqjjmmzl55cgju004vw08f83e98d28xmw44t4styhfhgsrwxydf68m2kup7j358zdrmhevqwr0hlqwt2eceaxcq7hezhx" -> PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca"),
      "lnbc10n1pwykdacpp5kegv2kdkxmetm2tpnzfgt4640n7mgxl95jnpc6fkz6uyjdwahw8sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdp5cqzysxqrrssjlny2skwtnnese9nmw99xlh7jwgtdxurhce2zcwsamevmj37kd5yzxzu55mt567seewmajra2hwyry5cv9kfzf02paerhs7tf9acdcgq24pqer" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc3100n1pwp370spp5ku7y6tfz5up840v00vgc2vmmqtpsu5ly98h09vxv9d7k9xtq8mrsdpjd35kw6r5de5kueevypkxjemgw3hxjmn89ssxc6t8dp6xu6twvucqp2sunrt8slx2wmvjzdv3vvlls9gez7g2gd37g2pwa4pnlswuxzy0w3hd5kkqdrpl4ylcdhvkvuamwjsfh79nkn52dq0qpzj8c4rf57jmgqschvrr" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc1500n1pwr7z8rpp5hyfkmnwwx7x902ys52du8pph6hdkarnqvj6fwhh9swfsg5lp94vsdpa2fjkzep6ypph2um5dajxjctvypmkzmrvv468xgrpwfjjqetkd9kzqctwvss8ycqzysxqr23s64a2h7gn25pchh8r6jpe236h925fylw2jcm4pd92w8hkmpflreph8r6s8jnnml0zu47qv6t2sj6frnle2cpanf6e027vsddgkl8hk7gpta89d0" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc1500n1pdl05v0pp5c4t5p3renelctlh0z4jpznyxna7lw9zhws868wktp8vtn8t5a8uqdpa2fjkzep6ypxxjemgw35kueeqfejhgam0wf4jqnrfw96kjerfw3ujq5r0dakq6cqzysxqr23s7k3ktaae69gpl2tfleyy2rsm0m6cy5yvf8uq7g4dmpyrwvfxzslnvryx5me4xh0fsp9jfjsqkuwpzx9ydwe6ndrm0eznarhdrfwn5gsp949n7x" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc1500n1pwyvxp3pp5ch8jx4g0ft0f6tzg008vr82wv92sredy07v46h7q3h3athx2nm2sdpa2fjkzep6ypyx7aeqfys8w6tndqsx67fqw35x2gzvv4jxwetjypvzqam0w4kxgcqzysxqr23s3hdgx90a6jcqgl84z36dv6kn6eg4klsaje2kdm84662rq7lzzzlycvne4l8d0steq5pctdp4ffeyhylgrt7ln92l8dyvrnsn9qg5qkgqrz2cra" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc1500n1pwr7z2ppp5cuzt0txjkkmpz6sgefdjjmdrsj9gl8fqyeu6hx7lj050f68yuceqdqvg9jxgg8zn2sscqzysxqr23s7442lgk6cj95qygw2hly9qw9zchhag5p5m3gyzrmws8namcsqh5nz2nm6a5sc2ln6jx59sln9a7t8vxtezels2exurr0gchz9gk0ufgpwczm3r" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc1500n1pd7u7g4pp5eam7uhxc0w4epnuflgkl62m64qu378nnhkg3vahkm7dhdcqnzl4sdqvg9jxgg8zn2sscqzysxqr23s870l2549nhsr2dfv9ehkl5z95p5rxpks5j2etr35e02z9r6haalrfjs7sz5y7wzenywp8t52w89c9u8taf9m76t2p0w0vxw243y7l4spqdue7w" -> PublicKey(hex"03e50492eab4107a773141bb419e107bda3de3d55652e6e1a41225f06a0bbf2d56"),
      "lnbc5u1pwq2jqzpp56zhpjmfm72e8p8vmfssspe07u7zmnm5hhgynafe4y4lwz6ypusvqdzsd35kw6r5de5kuemwv468wmmjddehgmmjv4ejucm0d40n2vpsta6hqan0w3jhxhmnw3hhye2fgs7nywfhcqp2tqnqpewrz28yrvvvyzjyrvwahuy595t4w4ar3cvt5cq9jx3rmxd4p7vjgmeylfkgjrssc66a9q9hhnd4aj7gqv2zj0jr2zt0gahnv0sp9y675y" -> PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      "lnbc10n1pw9pqp3pp562wg5n7atx369mt75feu233cnm5h508mx7j0d807lqe0w45gndnqdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdejcqzysxqrrsszfg9lfawdhnp2m785cqgzg4c85mvgct44xdzjea9t0vu4mc22u4prjjz5qd4y7uhgg3wm57muh5wfz8l04kgyq8juwql3vaffm23akspzkmj53" -> PublicKey(hex"02cda8c01b2303e91bec74c43093d5f1c4fd42a95671ae27bf853d7dfea9b78c06"),
      "lnbc90n1pwypjnppp5m870lhg8qjrykj6hfegawaq0ukzc099ntfezhm8jr48cw5ywgpwqdpl2pshjmt9de6zqen0wgsrjgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp2s0n2u7msmypy9dh96e6exfas434td6a7f5qy5shzyk4r9dxwv0zhyxcqjkmxgnnkjvqhthadhkqvvd66f8gxkdna3jqyzhnnhfs6w3qpme2zfz" -> PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca"),
      "lnbc100n1pdunsurpp5af2vzgyjtj2q48dxl8hpfv9cskwk7q5ahefzyy3zft6jyrc4uv2qdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnyvccqzysxqyd9uqpcp608auvkcr22672nhwqqtul0q6dqrxryfsstttlwyvkzttxt29mxyshley6u45gf0sxc0d9dxr5fk48tj4z2z0wh6asfxhlsea57qp45tfua" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc100n1pd6hzfgpp5au2d4u2f2gm9wyz34e9rls66q77cmtlw3tzu8h67gcdcvj0dsjdqdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnqvscqzysxqyd9uqxg5n7462ykgs8a23l3s029dun9374xza88nlf2e34nupmc042lgps7tpwd0ue0he0gdcpfmc5mshmxkgw0hfztyg4j463ux28nh2gagqage30p" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc50n1pdl052epp57549dnjwf2wqfz5hg8khu0wlkca8ggv72f9q7x76p0a7azkn3ljsdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnvvscqzysxqyd9uqa2z48kchpmnyafgq2qlt4pruwyjh93emh8cd5wczwy47pkx6qzarmvl28hrnqf98m2rnfa0gx4lnw2jvhlg9l4265240av6t9vdqpzsqntwwyx" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc100n1pd7cwrypp57m4rft00sh6za2x0jwe7cqknj568k9xajtpnspql8dd38xmd7musdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcngvscqzysxqyd9uqsxfmfv96q0d7r3qjymwsem02t5jhtq58a30q8lu5dy3jft7wahdq2f5vc5qqymgrrdyshff26ak7m7n0vqyf7t694vam4dcqkvnr65qp6wdch9" -> PublicKey(hex"03a9d79bcfab7feb0f24c3cd61a57f0f00de2225b6d31bce0bc4564efa3b1b5aaf"),
      "lnbc100n1pw9qjdgpp5lmycszp7pzce0rl29s40fhkg02v7vgrxaznr6ys5cawg437h80nsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdejcqzysxqrrss47kl34flydtmu2wnszuddrd0nwa6rnu4d339jfzje6hzk6an0uax3kteee2lgx5r0629wehjeseksz0uuakzwy47lmvy2g7hja7mnpsqjmdct9" -> PublicKey(hex"02cda8c01b2303e91bec74c43093d5f1c4fd42a95671ae27bf853d7dfea9b78c06"),
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu" -> PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"),
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q4psqqqqqqqqqqqqqqqpqsqq40wa3khl49yue3zsgm26jrepqr2eghqlx86rttutve3ugd05em86nsefzh4pfurpd9ek9w2vp95zxqnfe2u7ckudyahsa52q66tgzcp6t2dyk" -> PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"),
      "lnbc100n1pslczttpp5refxwyd5qvvnxsmswhqtqd50hdcwhk5edp02u3xpy6whf6eua3lqdq8w35hg6gsp56nrnqjjjj2g3wuhdwhy7r3sfu0wae603w9zme8wcq2f3myu3hm6qcqzrm9qrjgq7md5lu2hhkz657rs2a40xm2elaqda4krv6vy44my49x02azsqwr35puvgzjltd6dfth2awxcq49cx3srkl3zl34xhw7ppv840yf74wqq88rwr5" -> PublicKey(hex"036dc96e30210083a18762be096f13500004fc8af5bcca40f4872e18771ad58b4c"),
      "lnbc100n1pslczttpp5refxwyd5qvvnxsmswhqtqd50hdcwhk5edp02u3xpy6whf6eua3lqdq8w35hg6gsp56nrnqjjjj2g3wuhdwhy7r3sfu0wae603w9zme8wcq2f3myu3hm6qcqzrm9qr3gqjdynggx20rz4nh98uknmtp2wkwk95zru8lfmw0cz9s3t0xpevuzpzz4k34cprpg9jfc3yp8zc827psug69j4w4pkn70rrfddcqf9wnqqcm2nc4" -> PublicKey(hex"036dc96e30210083a18762be096f13500004fc8af5bcca40f4872e18771ad58b4c"),
      "lnbc100n1pslczttpp5refxwyd5qvvnxsmswhqtqd50hdcwhk5edp02u3xpy6whf6eua3lqdq8w35hg6gsp56nrnqjjjj2g3wuhdwhy7r3sfu0wae603w9zme8wcq2f3myu3hm6qcqzrm9q9sqsgqruuf6y6hd77533p6ufl3dapzzt55uj7t88mgty7hvfpy5lzvntpyn82j72fr3wqz985lh7l2f5pnju66nman5z09p24qvp2k8443skqqq38n4w" -> PublicKey(hex"036dc96e30210083a18762be096f13500004fc8af5bcca40f4872e18771ad58b4c"),
      "lnbc100n1pslczttpp5refxwyd5qvvnxsmswhqtqd50hdcwhk5edp02u3xpy6whf6eua3lqdq8w35hg6gsp56nrnqjjjj2g3wuhdwhy7r3sfu0wae603w9zme8wcq2f3myu3hm6qcqzrm9qxpqqsgqh88td9f8p8ls8r6devh9lhvppwqe6e0lkvehyu8ztu76m9s8nu2x0rfp5z9jmn2ta97mex2ne6yecvtz8r0qej62lvkngpaduhgytncqts4cxs" -> PublicKey(hex"036dc96e30210083a18762be096f13500004fc8af5bcca40f4872e18771ad58b4c"),
    )

    for ((req, nodeId) <- requests) {
      assert(Bolt11Invoice.fromString(req).nodeId === nodeId)
      assert(Bolt11Invoice.fromString(req).toString === req)
    }
  }

  test("no unknown feature in invoice"){
    assert(TestConstants.Alice.nodeParams.features.invoiceFeatures().unknown.nonEmpty)
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = TestConstants.Alice.nodeParams.features.invoiceFeatures())
    assert(invoice.features === Features[InvoiceFeature](Map[Feature with InvoiceFeature, FeatureSupport](PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, VariableLengthOnion -> Mandatory)))
    assert(Bolt11Invoice.fromString(invoice.toString) === invoice)
  }

  test("Invoices can't have high features"){
    assertThrows[Exception](createInvoiceUnsafe(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = Features[FeatureScope](Map[Feature with FeatureScope, FeatureSupport](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory), Set(UnknownFeature(424242)))))
  }
}
