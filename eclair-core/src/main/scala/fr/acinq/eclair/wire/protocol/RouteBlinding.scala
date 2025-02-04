/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.{ShortChannelId, UInt64}
import scodec.bits.ByteVector

import scala.util.Try

/**
 * Created by t-bast on 19/10/2021.
 */

sealed trait RouteBlindingEncryptedDataTlv extends Tlv

object RouteBlindingEncryptedDataTlv {

  /** Some padding can be added to ensure all payloads are the same size to improve privacy. */
  case class Padding(dummy: ByteVector) extends RouteBlindingEncryptedDataTlv

  /** Id of the outgoing channel, used to identify the next node. */
  case class OutgoingChannelId(shortChannelId: ShortChannelId) extends RouteBlindingEncryptedDataTlv

  /** Id of the next node. */
  case class OutgoingNodeId(nodeId: PublicKey) extends RouteBlindingEncryptedDataTlv

  /**
   * The final recipient may store some data in the encrypted payload for itself to avoid storing it locally.
   * It can for example put a payment_hash to verify that the route is used for the correct invoice.
   * It should use that field to detect when blinded routes are used outside of their intended use (malicious probing)
   * and react accordingly (ignore the message or send an error depending on the use-case).
   */
  case class PathId(data: ByteVector) extends RouteBlindingEncryptedDataTlv

  /** Blinding override for the rest of the route. */
  case class NextBlinding(blinding: PublicKey) extends RouteBlindingEncryptedDataTlv

}

object RouteBlindingEncryptedDataCodecs {

  import RouteBlindingEncryptedDataTlv._
  import fr.acinq.eclair.wire.protocol.CommonCodecs.{publicKey, shortchannelid, varint, varintoverflow}
  import scodec.Codec
  import scodec.bits.HexStringSyntax
  import scodec.codecs._

  private val padding: Codec[Padding] = variableSizeBytesLong(varintoverflow, "padding" | bytes).as[Padding]
  private val outgoingChannelId: Codec[OutgoingChannelId] = variableSizeBytesLong(varintoverflow, "short_channel_id" | shortchannelid).as[OutgoingChannelId]
  private val outgoingNodeId: Codec[OutgoingNodeId] = (("length" | constant(hex"21")) :: ("node_id" | publicKey)).as[OutgoingNodeId]
  private val pathId: Codec[PathId] = variableSizeBytesLong(varintoverflow, "path_id" | bytes).as[PathId]
  private val nextBlinding: Codec[NextBlinding] = (("length" | constant(hex"21")) :: ("blinding" | publicKey)).as[NextBlinding]

  private val encryptedDataTlvCodec = discriminated[RouteBlindingEncryptedDataTlv].by(varint)
    .typecase(UInt64(1), padding)
    .typecase(UInt64(2), outgoingChannelId)
    .typecase(UInt64(4), outgoingNodeId)
    .typecase(UInt64(6), pathId)
    .typecase(UInt64(8), nextBlinding)

  val encryptedDataCodec: Codec[TlvStream[RouteBlindingEncryptedDataTlv]] = TlvCodecs.tlvStream[RouteBlindingEncryptedDataTlv](encryptedDataTlvCodec).complete

  /**
   * Decrypt and decode the contents of an encrypted_recipient_data TLV field.
   *
   * @param nodePrivKey   this node's private key.
   * @param blindingKey   blinding point (usually provided in the lightning message).
   * @param encryptedData encrypted route blinding data (usually provided inside an onion).
   * @return decrypted contents of the encrypted recipient data, which usually contain information about the next node,
   *         and the blinding point that should be sent to the next node.
   */
  def decode(nodePrivKey: PrivateKey, blindingKey: PublicKey, encryptedData: ByteVector): Try[(TlvStream[RouteBlindingEncryptedDataTlv], PublicKey)] = {
    Sphinx.RouteBlinding.decryptPayload(nodePrivKey, blindingKey, encryptedData).flatMap {
      case (payload, nextBlindingKey) => encryptedDataCodec.decode(payload.bits).map(r => (r.value, nextBlindingKey)).toTry
    }
  }

}
