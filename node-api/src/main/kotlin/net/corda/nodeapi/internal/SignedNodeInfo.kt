package net.corda.nodeapi.internal

import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.verify
import net.corda.core.node.NodeInfo
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import java.security.SignatureException

/**
 * A signed [NodeInfo] object containing a signature for each identity. The list of signatures is expected
 * to be in the same order as the identities.
 */
// TODO Add signatures for composite keys. The current thinking is to make sure there is a signature for each leaf key
// that the node owns. This check can only be done by the network map server as it can check with the doorman if a node
// is part of a composite identity. This of course further requires the doorman being able to issue CSRs for composite
// public keys.
@CordaSerializable
class SignedNodeInfo(val raw: SerializedBytes<NodeInfo>, val signatures: List<DigitalSignature>) {
    fun verified(): NodeInfo {
        val nodeInfo = raw.deserialize()
        val identities = nodeInfo.legalIdentities.filterNot { it.owningKey is CompositeKey }

        if (identities.size < signatures.size) {
            throw SignatureException("Extra signatures. Found ${signatures.size} expected ${identities.size}")
        }
        if (identities.size > signatures.size) {
            throw SignatureException("Missing signatures. Found ${signatures.size} expected ${identities.size}")
        }

        val rawBytes = raw.bytes  // To avoid cloning the byte array multiple times
        identities.zip(signatures).forEach { (identity, signature) ->
            try {
                identity.owningKey.verify(rawBytes, signature)
            } catch (e: SignatureException) {
                throw SignatureException("$identity: ${e.message}")
            }
        }

        return nodeInfo
    }
}
