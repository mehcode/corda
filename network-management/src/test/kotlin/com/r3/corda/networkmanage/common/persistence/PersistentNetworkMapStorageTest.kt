package com.r3.corda.networkmanage.common.persistence

import com.r3.corda.networkmanage.TestBase
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.signWithCert
import net.corda.nodeapi.internal.createDevNetworkMapCa
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.nodeapi.internal.network.verifiedNetworkMapCert
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.internal.TestNodeInfoBuilder
import net.corda.testing.internal.createDevIntermediateCaCertPath
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.cert.X509Certificate
import kotlin.test.assertEquals

class PersistentNetworkMapStorageTest : TestBase() {
    private lateinit var persistence: CordaPersistence
    private lateinit var networkMapStorage: PersistentNetworkMapStorage
    private lateinit var nodeInfoStorage: PersistentNodeInfoStorage
    private lateinit var requestStorage: PersistentCertificateRequestStorage

    private lateinit var rootCaCert: X509Certificate
    private lateinit var networkMapCa: CertificateAndKeyPair

    @Before
    fun startDb() {
        val (rootCa) = createDevIntermediateCaCertPath()
        rootCaCert = rootCa.certificate
        networkMapCa = createDevNetworkMapCa(rootCa)
        persistence = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(runMigration = true))
        networkMapStorage = PersistentNetworkMapStorage(persistence)
        nodeInfoStorage = PersistentNodeInfoStorage(persistence)
        requestStorage = PersistentCertificateRequestStorage(persistence)
    }

    @After
    fun closeDb() {
        persistence.close()
    }

    @Test
    fun `saveNetworkMap and saveNetworkParameters create current network map and parameters`() {
        // given
        // Create node info.
        val signedNodeInfo = createValidSignedNodeInfo("Test")
        val nodeInfoHash = nodeInfoStorage.putNodeInfo(signedNodeInfo)

        val networkParameters = testNetworkParameters(emptyList())
        val parametersSignature = networkParameters.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate).sig
        // Create network parameters
        val networkParametersHash = networkMapStorage.saveNetworkParameters(networkParameters, parametersSignature)
        val networkMap = NetworkMap(listOf(nodeInfoHash), networkParametersHash)
        val signedNetworkMap = networkMap.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate)

        // when
        networkMapStorage.saveNetworkMap(signedNetworkMap)

        // then
        val persistedSignedNetworkMap = networkMapStorage.getCurrentNetworkMap()
        val persistedSignedParameters = networkMapStorage.getCurrentSignedNetworkParameters()

        assertEquals(networkParameters, persistedSignedParameters?.verifiedNetworkMapCert(rootCaCert))
        assertEquals(parametersSignature, persistedSignedParameters?.sig)
        assertEquals(signedNetworkMap.sig, persistedSignedNetworkMap?.sig)
        assertEquals(signedNetworkMap.verifiedNetworkMapCert(rootCaCert), persistedSignedNetworkMap?.verifiedNetworkMapCert(rootCaCert))
        assertEquals(signedNetworkMap.verifiedNetworkMapCert(rootCaCert).networkParameterHash, persistedSignedParameters?.raw?.hash)
    }

    @Test
    fun `getLatestNetworkParameters returns last inserted`() {
        val params1 = testNetworkParameters(emptyList(), minimumPlatformVersion = 1)
        val params2 = testNetworkParameters(emptyList(), minimumPlatformVersion = 2)
        // given
        networkMapStorage.saveNetworkParameters(params1, params1.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate).sig)
        // We may have not signed them yet.
        networkMapStorage.saveNetworkParameters(params2, null)

        // when
        val latest = networkMapStorage.getLatestUnsignedNetworkParameters()
        // then
        assertEquals(2, latest.minimumPlatformVersion)
    }

    @Test
    fun `getCurrentNetworkParameters returns current network map parameters`() {
        // given
        // Create network parameters
        val testParameters1 = testNetworkParameters(emptyList())
        val networkParametersHash = networkMapStorage.saveNetworkParameters(testParameters1, testParameters1.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate).sig)
        // Create empty network map

        // Sign network map making it current network map
        val networkMap = NetworkMap(emptyList(), networkParametersHash)
        val signedNetworkMap = networkMap.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate)
        networkMapStorage.saveNetworkMap(signedNetworkMap)

        // Create new network parameters
        val testParameters2 = testNetworkParameters(emptyList(), minimumPlatformVersion = 2)
        networkMapStorage.saveNetworkParameters(testParameters2, testParameters2.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate).sig)

        // when
        val result = networkMapStorage.getCurrentSignedNetworkParameters()?.verifiedNetworkMapCert(rootCaCert)

        // then
        assertEquals(1, result?.minimumPlatformVersion)
    }

    @Test
    fun `getValidNodeInfoHashes returns only valid and signed node info hashes`() {
        // given
        // Create node infos.
        val signedNodeInfoA = createValidSignedNodeInfo("TestA")
        val signedNodeInfoB = createValidSignedNodeInfo("TestB")

        // Put signed node info data
        val nodeInfoHashA = nodeInfoStorage.putNodeInfo(signedNodeInfoA)
        val nodeInfoHashB = nodeInfoStorage.putNodeInfo(signedNodeInfoB)

        // Create network parameters
        val testParameters = testNetworkParameters(emptyList())
        val networkParametersHash = networkMapStorage.saveNetworkParameters(testParameters, testParameters.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate).sig)
        val networkMap = NetworkMap(listOf(nodeInfoHashA), networkParametersHash)
        val signedNetworkMap = networkMap.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate)

        // Sign network map
        networkMapStorage.saveNetworkMap(signedNetworkMap)

        // when
        val validNodeInfoHash = networkMapStorage.getNodeInfoHashes(CertificateStatus.VALID)

        // then
        assertThat(validNodeInfoHash).containsOnly(nodeInfoHashA, nodeInfoHashB)
    }

    private fun createValidSignedNodeInfo(organisation: String): NodeInfoWithSigned {
        val nodeInfoBuilder = TestNodeInfoBuilder()
        val requestId = requestStorage.saveRequest(createRequest(organisation).first)
        requestStorage.markRequestTicketCreated(requestId)
        requestStorage.approveRequest(requestId, "TestUser")
        val (identity) = nodeInfoBuilder.addIdentity(CordaX500Name(organisation, "London", "GB"))
        val nodeCaCertPath = X509CertificateFactory().generateCertPath(identity.certPath.certificates.drop(1))
        requestStorage.putCertificatePath(requestId, nodeCaCertPath, emptyList())
        return NodeInfoWithSigned(nodeInfoBuilder.buildWithSigned().second)
    }
}