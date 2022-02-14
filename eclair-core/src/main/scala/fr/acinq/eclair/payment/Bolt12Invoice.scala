/*
 * Copyright 2022 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.wire.protocol.OfferCodecs.{Bech32WithoutChecksum, invoiceCodec, invoiceTlvCodec}
import fr.acinq.eclair.wire.protocol.Offers._
import fr.acinq.eclair.wire.protocol.{Offers, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, Features, InvoiceFeature, MilliSatoshi, TimestampSecond}
import scodec.bits.ByteVector

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Lightning Bolt 12 invoice
 * see https://github.com/lightning/bolts/blob/master/12-offer-encoding.md
 */
case class Bolt12Invoice(records: TlvStream[InvoiceTlv]) extends Invoice {

  import Bolt12Invoice._

  require(records.get[Amount].nonEmpty, "bolt 12 invoices must provide an amount")
  require(records.get[NodeId].nonEmpty, "bolt 12 invoices must provide a node id")
  require(records.get[PaymentHash].nonEmpty, "bolt 12 invoices must provide a payment hash")
  require(records.get[Description].nonEmpty, "bolt 12 invoices must provide a description")
  require(records.get[CreatedAt].nonEmpty, "bolt 12 invoices must provide a creation timestamp")
  require(records.get[Signature].nonEmpty, "bolt 12 invoices must provide a signature")

  val amount: MilliSatoshi = records.get[Amount].map(_.amount).get

  override val amount_opt: Option[MilliSatoshi] = Some(amount)

  override val nodeId: Crypto.PublicKey = records.get[NodeId].get.nodeIds.head

  override val paymentHash: ByteVector32 = records.get[PaymentHash].get.hash

  override val paymentSecret: Option[ByteVector32] = None

  override val paymentMetadata: Option[ByteVector] = None

  override val description: Either[String, ByteVector32] = Left(records.get[Description].get.description)

  override val routingInfo: Seq[Seq[ExtraHop]] = Seq.empty

  override val createdAt: TimestampSecond = records.get[CreatedAt].get.timestamp

  override val relativeExpiry: FiniteDuration = FiniteDuration(records.get[RelativeExpiry].map(_.seconds).getOrElse(DEFAULT_EXPIRY_SECONDS), TimeUnit.SECONDS)

  override val minFinalCltvExpiryDelta: Option[CltvExpiryDelta] = records.get[Cltv].map(_.minFinalCltvExpiry).orElse(Some(DEFAULT_MIN_FINAL_EXPIRY_DELTA))

  override val features: Features[InvoiceFeature] = records.get[FeaturesTlv].map(_.features.invoiceFeatures()).getOrElse(Features.empty)

  override def toString: String = Bech32WithoutChecksum.encode(hrp, invoiceCodec, this)

  val chain: ByteVector32 = records.get[Chain].map(_.hash).getOrElse(Block.LivenetGenesisBlock.hash)

  val offerId: Option[ByteVector32] = records.get[OfferId].map(_.offerId)

  val blindedPaths: Option[Seq[BlindedPathWithPayInfo]] = records.get[Paths].map(_.paths).map(paths => {
    val blindedPay_opt = records.get[BlindedPay].map(_.payInfos)
    require(blindedPay_opt.nonEmpty, "when including blinded paths, a blinded_payinfo field must be provided for each of the blinded paths")
    val capacities = records.get[BlindedCapacities].map(_.capacities)
    require(capacities.forall(_.length == paths.length), "when including blinded capacities, an amount must be included for each blinded path")
    paths.zipWithIndex.map { case (path, i) => BlindedPathWithPayInfo(path, blindedPay_opt.get(i), capacities.map(c => c(i))) }
  })

  val issuer: Option[String] = records.get[Issuer].map(_.issuer)

  val quantity: Option[Long] = records.get[Quantity].map(_.quantity)

  val refundFor: Option[ByteVector32] = records.get[RefundFor].map(_.refundedPaymentHash)

  val payerKey: Option[ByteVector32] = records.get[PayerKey].map(_.publicKey)

  val payerNote: Option[String] = records.get[PayerNote].map(_.note)

  val payerInfo: Option[ByteVector] = records.get[PayerInfo].map(_.info)

  val fallbacks: Option[Seq[FallbackAddress]] = records.get[Fallbacks].map(_.addresses)

  val refundSignature: Option[ByteVector64] = records.get[RefundSignature].map(_.signature)

  val replaceInvoice: Option[ByteVector32] = records.get[ReplaceInvoice].map(_.paymentHash)

  val signature: ByteVector64 = records.get[Signature].get.signature

  def isValidFor(offer: Offer, request: InvoiceRequest): Boolean = {
    Offers.xOnlyPublicKey(nodeId) == Offers.xOnlyPublicKey(offer.nodeId) &&
      checkSignature() &&
      offerId.contains(offer.offerId) &&
      offer.chains.contains(chain) &&
      request.chain == chain &&
      !isExpired() &&
      // The recipient is allowed to provide a discount (e.g. when paying for multiple items).
      request.amount.forall(requestedAmount => amount <= requestedAmount) &&
      quantity == request.quantity_opt &&
      payerKey.contains(request.payerKey) &&
      payerInfo == request.payerInfo &&
      // Bolt 12: MUST reject the invoice if payer_note is set, and was unset or not equal to the field in the invoice_request.
      payerNote.forall(request.payerNote.contains(_)) &&
      issuer == offer.issuer &&
      request.features.areSupported(features)
  }

  def checkRefundSignature(): Boolean = {
    (refundSignature, refundFor, payerKey) match {
      case (Some(sig), Some(hash), Some(key)) => verifySchnorr(signatureTag("payer_signature"), hash, sig, key)
      case _ => false
    }
  }

  def checkSignature(): Boolean = {
    verifySchnorr(signatureTag("signature"), rootHash(Offers.removeSignature(records), invoiceTlvCodec), signature, Offers.xOnlyPublicKey(nodeId))
  }
}

object Bolt12Invoice {
  val hrp = "lni"
  val DEFAULT_EXPIRY_SECONDS: Long = 7200
  val DEFAULT_MIN_FINAL_EXPIRY_DELTA: CltvExpiryDelta = CltvExpiryDelta(18)

  case class BlindedPathWithPayInfo(route: RouteBlinding.BlindedRoute, payInfo: PayInfo, capacity: Option[MilliSatoshi])

  /**
   * Creates an invoice for a given offer and invoice request.
   *
   * @param amount    the amount of the invoice (which may be different from the offer and invoice request, for example when applying a discount)
   * @param offer     the offer this invoice corresponds to
   * @param request   the request this invoice responds to
   * @param preimage  the preimage to use for the payment
   * @param nodeKey   the key that was used to generate the offer, may be different from our public nodeId if we're hiding behind a blinded route
   * @param features  invoice features
   * @param chain_opt (optional) should only be provided when the invoice is not for bitcoin mainnet
   */
  def apply(amount: MilliSatoshi, offer: Offer, request: InvoiceRequest, preimage: ByteVector32, nodeKey: PrivateKey, features: Features[InvoiceFeature], chain_opt: Option[ByteVector32] = None): Bolt12Invoice = {
    val tlvs: Seq[InvoiceTlv] = Seq(
      chain_opt.map(Chain).orElse(request.records.get[Chain]),
      Some(CreatedAt(TimestampSecond.now())),
      Some(PaymentHash(Crypto.sha256(preimage))),
      Some(OfferId(offer.offerId)),
      Some(NodeId(nodeKey.publicKey)),
      Some(Amount(amount)),
      Some(Description(offer.description)),
      request.quantity_opt.map(Quantity),
      Some(PayerKey(request.payerKey)),
      request.payerInfo.map(PayerInfo),
      request.payerNote.map(PayerNote),
      request.replaceInvoice.map(ReplaceInvoice),
      offer.issuer.map(Issuer),
      Some(FeaturesTlv(features.unscoped()))
    ).flatten
    val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
    Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
  }

  def signatureTag(fieldName: String): String = "lightning" + "invoice" + fieldName

  def fromString(input: String): Try[Bolt12Invoice] = Bech32WithoutChecksum.decode(hrp, invoiceCodec, input.toLowerCase)
}
