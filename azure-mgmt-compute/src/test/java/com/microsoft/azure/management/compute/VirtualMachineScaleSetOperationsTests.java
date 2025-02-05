/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azure.management.compute;

import com.microsoft.azure.PagedList;
import com.microsoft.azure.SubResource;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.graphrbac.BuiltInRole;
import com.microsoft.azure.management.graphrbac.RoleAssignment;
import com.microsoft.azure.management.graphrbac.ServicePrincipal;
import com.microsoft.azure.management.keyvault.Secret;
import com.microsoft.azure.management.keyvault.Vault;
import com.microsoft.azure.management.network.ApplicationSecurityGroup;
import com.microsoft.azure.management.network.LoadBalancer;
import com.microsoft.azure.management.network.LoadBalancerBackend;
import com.microsoft.azure.management.network.LoadBalancerInboundNatRule;
import com.microsoft.azure.management.network.LoadBalancerSkuType;
import com.microsoft.azure.management.network.LoadBalancingRule;
import com.microsoft.azure.management.network.Network;
import com.microsoft.azure.management.network.NetworkSecurityGroup;
import com.microsoft.azure.management.network.PublicIPAddress;
import com.microsoft.azure.management.network.SecurityRuleProtocol;
import com.microsoft.azure.management.network.VirtualMachineScaleSetNetworkInterface;
import com.microsoft.azure.management.network.VirtualMachineScaleSetNicIPConfiguration;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.management.resources.fluentcore.arm.AvailabilityZoneId;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.management.storage.StorageAccountKey;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.rest.RestClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VirtualMachineScaleSetOperationsTests extends ComputeManagementTest {
    private static String RG_NAME = "";
    private static final Region REGION = Region.US_EAST;

    @Override
    protected void initializeClients(RestClient restClient, String defaultSubscription, String domain) {
        RG_NAME = generateRandomResourceName("javacsmrg", 15);
        super.initializeClients(restClient, defaultSubscription, domain);
    }
    @Override
    protected void cleanUpResources() {
        if (RG_NAME != null) {
            resourceManager.resourceGroups().deleteByName(RG_NAME);
        }
    }

    @Test
    public void canUpdateVirtualMachineScaleSetWithExtensionProtectedSettings() throws Exception {
        final String vmssName = generateRandomResourceName("vmss", 10);
        final String uname = "jvuser";
        final String password = "123OData!@#123";

        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION)
                .create();

        StorageAccount storageAccount = this.storageManager.storageAccounts()
                .define(generateRandomResourceName("stg", 15))
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .create();

        List<StorageAccountKey> keys = storageAccount.getKeys();
        Assert.assertNotNull(keys);
        Assert.assertTrue(keys.size() > 0);
        String storageAccountKey = keys.get(0).value();

        final String storageConnectionString = String.format("DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s",
                storageAccount.name(),
                storageAccountKey);
        // Get the script to upload
        //
        InputStream scriptFileAsStream = VirtualMachineScaleSetOperationsTests
                .class
                .getResourceAsStream("/install_apache.sh");
        // Get the size of the stream
        //
        int fileSize;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int bytesRead;
        while ((bytesRead = scriptFileAsStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        fileSize = outputStream.size();
        outputStream.close();
        // Upload the script file as block blob
        //
        URI fileUri;
        if (isPlaybackMode()) {
            fileUri = new URI("http://nonexisting.blob.core.windows.net/scripts/install_apache.sh");
        } else {
            CloudStorageAccount account = CloudStorageAccount.parse(storageConnectionString);
            CloudBlobClient cloudBlobClient = account.createCloudBlobClient();
            CloudBlobContainer container = cloudBlobClient.getContainerReference("scripts");
            container.createIfNotExists();
            CloudBlockBlob blob = container.getBlockBlobReference("install_apache.sh");
            blob.upload(scriptFileAsStream, fileSize);
            fileUri = blob.getUri();
        }
        List<String> fileUris = new ArrayList<>();
        fileUris.add(fileUri.toString());

        Network network = this.networkManager
                .networks()
                .define(generateRandomResourceName("vmssvnet", 15))
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();

        VirtualMachineScaleSet virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets().define(vmssName)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_A0)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withoutPrimaryInternetFacingLoadBalancer()
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername(uname)
                .withRootPassword(password)
                .withUnmanagedDisks()
                .withNewStorageAccount(generateRandomResourceName("stg", 15))
                .withExistingStorageAccount(storageAccount)
                .defineNewExtension("CustomScriptForLinux")
                .withPublisher("Microsoft.OSTCExtensions")
                .withType("CustomScriptForLinux")
                .withVersion("1.4")
                .withMinorVersionAutoUpgrade()
                .withPublicSetting("fileUris",fileUris)
                .withProtectedSetting("commandToExecute", "bash install_apache.sh")
                .withProtectedSetting("storageAccountName", storageAccount.name())
                .withProtectedSetting("storageAccountKey", storageAccountKey)
                .attach()
                .create();
        // Validate extensions after create
        //
        Map<String, VirtualMachineScaleSetExtension> extensions = virtualMachineScaleSet.extensions();
        Assert.assertNotNull(extensions);
        Assert.assertEquals(1, extensions.size());
        Assert.assertTrue(extensions.containsKey("CustomScriptForLinux"));
        VirtualMachineScaleSetExtension extension = extensions.get("CustomScriptForLinux");
        Assert.assertNotNull(extension.publicSettings());
        Assert.assertEquals(1, extension.publicSettings().size());
        Assert.assertNotNull(extension.publicSettingsAsJsonString());
        // Retrieve scale set
        VirtualMachineScaleSet scaleSet = this.computeManager
                .virtualMachineScaleSets()
                .getById(virtualMachineScaleSet.id());
        // Validate extensions after get
        //
        extensions = virtualMachineScaleSet.extensions();
        Assert.assertNotNull(extensions);
        Assert.assertEquals(1, extensions.size());
        Assert.assertTrue(extensions.containsKey("CustomScriptForLinux"));
        extension = extensions.get("CustomScriptForLinux");
        Assert.assertNotNull(extension.publicSettings());
        Assert.assertEquals(1, extension.publicSettings().size());
        Assert.assertNotNull(extension.publicSettingsAsJsonString());
        // Update VMSS capacity
        //
        int newCapacity = (int) (scaleSet.capacity() + 1);
        virtualMachineScaleSet.update()
                .withCapacity(newCapacity)
                .apply();
        // Validate extensions after update
        //
        extensions = virtualMachineScaleSet.extensions();
        Assert.assertNotNull(extensions);
        Assert.assertEquals(1, extensions.size());
        Assert.assertTrue(extensions.containsKey("CustomScriptForLinux"));
        extension = extensions.get("CustomScriptForLinux");
        Assert.assertNotNull(extension.publicSettings());
        Assert.assertEquals(1, extension.publicSettings().size());
        Assert.assertNotNull(extension.publicSettingsAsJsonString());
    }

    @Test
    public void canCreateVirtualMachineScaleSetWithCustomScriptExtension() throws Exception {
        final String vmssName = generateRandomResourceName("vmss", 10);
        final String uname = "jvuser";
        final String password = "123OData!@#123";
        final String apacheInstallScript = "https://raw.githubusercontent.com/Azure/azure-libraries-for-java/master/azure-mgmt-compute/src/test/assets/install_apache.sh";
        final String installCommand = "bash install_apache.sh Abc.123x(";
        List<String> fileUris = new ArrayList<>();
        fileUris.add(apacheInstallScript);

        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION)
                .create();

        Network network = this.networkManager
                .networks()
                .define(generateRandomResourceName("vmssvnet", 15))
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();

        LoadBalancer publicLoadBalancer = createHttpLoadBalancers(REGION, resourceGroup, "1");
        VirtualMachineScaleSet virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets().define(vmssName)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_A0)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withExistingPrimaryInternetFacingLoadBalancer(publicLoadBalancer)
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername(uname)
                .withRootPassword(password)
                .withUnmanagedDisks()
                .withNewStorageAccount(generateRandomResourceName("stg", 15))
                .withNewStorageAccount(generateRandomResourceName("stg", 15))
                .defineNewExtension("CustomScriptForLinux")
                .withPublisher("Microsoft.OSTCExtensions")
                .withType("CustomScriptForLinux")
                .withVersion("1.4")
                .withMinorVersionAutoUpgrade()
                .withPublicSetting("fileUris",fileUris)
                .withPublicSetting("commandToExecute", installCommand)
                .attach()
                .withUpgradeMode(UpgradeMode.MANUAL)
                .create();

        checkVMInstances(virtualMachineScaleSet);

        List<String> publicIPAddressIds = virtualMachineScaleSet.primaryPublicIPAddressIds();
        PublicIPAddress publicIPAddress = this.networkManager.publicIPAddresses()
                .getById(publicIPAddressIds.get(0));

        String fqdn = publicIPAddress.fqdn();
        // Assert public load balancing connection
        if (!isPlaybackMode()) {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("http://" + fqdn)
                    .build();
            Response response = client.newCall(request).execute();
            Assert.assertEquals(response.code(), 200);
        }

        // Check SSH to VM instances via Nat rule
        //
        for (VirtualMachineScaleSetVM vm : virtualMachineScaleSet.virtualMachines().list()) {
            PagedList<VirtualMachineScaleSetNetworkInterface> networkInterfaces = vm.listNetworkInterfaces();
            Assert.assertEquals(networkInterfaces.size(), 1);
            VirtualMachineScaleSetNetworkInterface networkInterface = networkInterfaces.get(0);
            VirtualMachineScaleSetNicIPConfiguration primaryIpConfig = null;
            primaryIpConfig = networkInterface.primaryIPConfiguration();
            Assert.assertNotNull(primaryIpConfig);
            Integer sshFrontendPort = null;
            List<LoadBalancerInboundNatRule> natRules = primaryIpConfig.listAssociatedLoadBalancerInboundNatRules();
            for (LoadBalancerInboundNatRule natRule : natRules) {
                if (natRule.backendPort() == 22) {
                    sshFrontendPort = natRule.frontendPort();
                    break;
                }
            }
            Assert.assertNotNull(sshFrontendPort);

            this.sleep(1000 * 60); // Wait some time for VM to be available
            this.ensureCanDoSsh(fqdn, sshFrontendPort, uname, password);
        }
    }

    @Test
    public void canCreateVirtualMachineScaleSetWithOptionalNetworkSettings() throws Exception {
        final String vmss_name = generateRandomResourceName("vmss", 10);
        final String vmssVmDnsLabel = generateRandomResourceName("pip", 10);
        final String nsgName = generateRandomResourceName("nsg", 10);
        final String asgName = generateRandomResourceName("asg", 8);

        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION)
                .create();

        Network network = this.networkManager
                .networks()
                .define("vmssvnet")
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();

        ApplicationSecurityGroup asg = this.networkManager
                .applicationSecurityGroups()
                .define(asgName)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .create();

        // Create VMSS with instance public ip
        VirtualMachineScaleSet virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets()
                .define(vmss_name)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_D3_V2)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withoutPrimaryInternetFacingLoadBalancer()
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername("jvuser")
                .withRootPassword("123OData!@#123")
                .withVirtualMachinePublicIp(vmssVmDnsLabel)
                .withExistingApplicationSecurityGroup(asg)
                .create();

        VirtualMachineScaleSetPublicIPAddressConfiguration currentIpConfig = virtualMachineScaleSet.virtualMachinePublicIpConfig();

        Assert.assertNotNull(currentIpConfig);
        Assert.assertNotNull(currentIpConfig.dnsSettings());
        Assert.assertNotNull(currentIpConfig.dnsSettings().domainNameLabel());

        currentIpConfig.withIdleTimeoutInMinutes(20);

        virtualMachineScaleSet.update()
                .withVirtualMachinePublicIp(currentIpConfig)
                .apply();

        currentIpConfig = virtualMachineScaleSet.virtualMachinePublicIpConfig();
        Assert.assertNotNull(currentIpConfig);
        Assert.assertNotNull(currentIpConfig.idleTimeoutInMinutes());
        Assert.assertEquals((long) 20, (long) currentIpConfig.idleTimeoutInMinutes());

        virtualMachineScaleSet.refresh();
        currentIpConfig = virtualMachineScaleSet.virtualMachinePublicIpConfig();
        Assert.assertNotNull(currentIpConfig);
        Assert.assertNotNull(currentIpConfig.idleTimeoutInMinutes());
        Assert.assertEquals((long) 20, (long) currentIpConfig.idleTimeoutInMinutes());

        List<String> asgIds = virtualMachineScaleSet.applicationSecurityGroupIds();
        Assert.assertNotNull(asgIds);
        Assert.assertEquals(1, asgIds.size());

        NetworkSecurityGroup nsg = networkManager.networkSecurityGroups().define(nsgName)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .defineRule("rule1")
                    .allowOutbound()
                    .fromAnyAddress()
                    .fromPort(80)
                    .toAnyAddress()
                    .toPort(80)
                    .withProtocol(SecurityRuleProtocol.TCP)
                    .attach()
                .create();
        virtualMachineScaleSet.deallocate();
        virtualMachineScaleSet.update()
                .withIpForwarding()
                .withAcceleratedNetworking()
                .withExistingNetworkSecurityGroup(nsg)
                .apply();

        Assert.assertTrue(virtualMachineScaleSet.isIpForwardingEnabled());
        Assert.assertTrue(virtualMachineScaleSet.isAcceleratedNetworkingEnabled());
        Assert.assertNotNull(virtualMachineScaleSet.networkSecurityGroupId());
        //
        virtualMachineScaleSet.refresh();
        //
        Assert.assertTrue(virtualMachineScaleSet.isIpForwardingEnabled());
        Assert.assertTrue(virtualMachineScaleSet.isAcceleratedNetworkingEnabled());
        Assert.assertNotNull(virtualMachineScaleSet.networkSecurityGroupId());

        virtualMachineScaleSet.update()
                .withoutIpForwarding()
                .withoutAcceleratedNetworking()
                .withoutNetworkSecurityGroup()
                .apply();
        Assert.assertFalse(virtualMachineScaleSet.isIpForwardingEnabled());
        Assert.assertFalse(virtualMachineScaleSet.isAcceleratedNetworkingEnabled());
        Assert.assertNull(virtualMachineScaleSet.networkSecurityGroupId());
    }


    @Test
    @Ignore("Mock framework doesn't support data plane")
    public void canCreateVirtualMachineScaleSetWithSecret() throws Exception {
        final String vmss_name = generateRandomResourceName("vmss", 10);
        final String vaultName = generateRandomResourceName("vlt", 10);
        final String secretName = generateRandomResourceName("srt", 10);


        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION)
                .create();

        Network network = this.networkManager
                .networks()
                .define("vmssvnet")
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();


        LoadBalancer publicLoadBalancer = createInternetFacingLoadBalancer(REGION,
                resourceGroup,
                "1",
                LoadBalancerSkuType.BASIC);

        List<String> backends = new ArrayList<>();
        for (String backend : publicLoadBalancer.backends().keySet()) {
            backends.add(backend);
        }
        Assert.assertTrue(backends.size() == 2);

        ApplicationTokenCredentials credentials = ApplicationTokenCredentials.fromFile(new File(System.getenv("AZURE_AUTH_LOCATION")));
        Vault vault = this.keyVaultManager.vaults().define(vaultName)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .defineAccessPolicy()
                .forServicePrincipal(credentials.clientId())
                .allowSecretAllPermissions()
                .attach()
                .withDeploymentEnabled()
                .create();
        final InputStream embeddedJsonConfig = VirtualMachineExtensionOperationsTests.class.getResourceAsStream("/myTest.txt");
        String secretValue = IOUtils.toString(embeddedJsonConfig);
        Secret secret = vault.secrets().define(secretName)
                .withValue(secretValue)
                .create();
        List<VaultCertificate> certs = new ArrayList<>();
        certs.add(new VaultCertificate().withCertificateUrl(secret.id()));
        List<VaultSecretGroup> group = new ArrayList<>();
        group.add(new VaultSecretGroup().withSourceVault(new SubResource().withId(vault.id()))
                .withVaultCertificates(certs));

        VirtualMachineScaleSet virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets()
                .define(vmss_name)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_A0)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withExistingPrimaryInternetFacingLoadBalancer(publicLoadBalancer)
                .withPrimaryInternetFacingLoadBalancerBackends(backends.get(0), backends.get(1))
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername("jvuser")
                .withRootPassword("123OData!@#123")
                .withSecrets(group)
                .withNewStorageAccount(generateRandomResourceName("stg", 15))
                .withNewStorageAccount(generateRandomResourceName("stg3", 15))
                .withUpgradeMode(UpgradeMode.MANUAL)
                .create();

        for (VirtualMachineScaleSetVM vm : virtualMachineScaleSet.virtualMachines().list()) {
            Assert.assertTrue(vm.osProfile().secrets().size() > 0);
        }

        virtualMachineScaleSet
                .update()
                .withoutSecrets()
                .apply();

        for (VirtualMachineScaleSetVM vm : virtualMachineScaleSet.virtualMachines().list()) {
            Assert.assertTrue(vm.osProfile().secrets().size() == 0);
        }
    }
    public void canCreateVirtualMachineScaleSet() throws Exception {
        final String vmss_name = generateRandomResourceName("vmss", 10);
        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION)
                .create();

        Network network = this.networkManager
                .networks()
                .define("vmssvnet")
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();

        LoadBalancer publicLoadBalancer = createInternetFacingLoadBalancer(REGION,
                resourceGroup,
                "1",
                LoadBalancerSkuType.BASIC);
        List<String> backends = new ArrayList<>();
        for (String backend : publicLoadBalancer.backends().keySet()) {
            backends.add(backend);
        }
        Assert.assertTrue(backends.size() == 2);

        VirtualMachineScaleSet virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets()
                .define(vmss_name)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_A0)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withExistingPrimaryInternetFacingLoadBalancer(publicLoadBalancer)
                .withPrimaryInternetFacingLoadBalancerBackends(backends.get(0), backends.get(1))
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername("jvuser")
                .withRootPassword("123OData!@#123")
                .withUnmanagedDisks()
                .withNewStorageAccount(generateRandomResourceName("stg", 15))
                .withNewStorageAccount(generateRandomResourceName("stg", 15))
                .create();

        // Validate Network specific properties (LB, VNet, NIC, IPConfig etc..)
        //
        Assert.assertNull(virtualMachineScaleSet.getPrimaryInternalLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternalLoadBalancerBackends().size() == 0);
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternalLoadBalancerInboundNatPools().size() == 0);

        Assert.assertNotNull(virtualMachineScaleSet.getPrimaryInternetFacingLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternetFacingLoadBalancerBackends().size() == 2);
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternetFacingLoadBalancerInboundNatPools().size() == 2);

        Network primaryNetwork = virtualMachineScaleSet.getPrimaryNetwork();
        Assert.assertNotNull(primaryNetwork.id());

        List<VirtualMachineScaleSetNetworkInterface> nics = virtualMachineScaleSet.listNetworkInterfaces();
        int nicCount = 0;
        for (VirtualMachineScaleSetNetworkInterface nic : nics) {
            nicCount++;
            Assert.assertNotNull(nic.id());
            Assert.assertTrue(nic.virtualMachineId().toLowerCase().startsWith(virtualMachineScaleSet.id().toLowerCase()));
            Assert.assertNotNull(nic.macAddress());
            Assert.assertNotNull(nic.dnsServers());
            Assert.assertNotNull(nic.appliedDnsServers());
            Map<String, VirtualMachineScaleSetNicIPConfiguration> ipConfigs =  nic.ipConfigurations();
            Assert.assertEquals(ipConfigs.size(), 1);
            for (Map.Entry<String, VirtualMachineScaleSetNicIPConfiguration> entry :ipConfigs.entrySet()) {
                VirtualMachineScaleSetNicIPConfiguration ipConfig = entry.getValue();
                Assert.assertNotNull(ipConfig);
                Assert.assertTrue(ipConfig.isPrimary());
                Assert.assertNotNull(ipConfig.subnetName());
                Assert.assertTrue(primaryNetwork.id().toLowerCase().equalsIgnoreCase(ipConfig.networkId()));
                Assert.assertNotNull(ipConfig.privateIPAddress());
                Assert.assertNotNull(ipConfig.privateIPAddressVersion());
                Assert.assertNotNull(ipConfig.privateIPAllocationMethod());
                List<LoadBalancerBackend> lbBackends = ipConfig.listAssociatedLoadBalancerBackends();
                // VMSS is created with a internet facing LB with two Backend pools so there will be two
                // backends in ip-config as well
                Assert.assertEquals(lbBackends.size(), 2);
                for (LoadBalancerBackend lbBackend : lbBackends) {
                    Map<String, LoadBalancingRule> lbRules = lbBackend.loadBalancingRules();
                    Assert.assertEquals(lbRules.size(), 1);
                    for (Map.Entry<String, LoadBalancingRule> ruleEntry : lbRules.entrySet()) {
                        LoadBalancingRule rule = ruleEntry.getValue();
                        Assert.assertNotNull(rule);
                        Assert.assertTrue((rule.frontendPort() == 80 && rule.backendPort() == 80)
                                || (rule.frontendPort() == 443 && rule.backendPort() == 443));
                    }
                }
                List<LoadBalancerInboundNatRule> lbNatRules = ipConfig.listAssociatedLoadBalancerInboundNatRules();
                // VMSS is created with a internet facing LB with two nat pools so there will be two
                //  nat rules in ip-config as well
                Assert.assertEquals(lbNatRules.size(), 2);
                for (LoadBalancerInboundNatRule lbNatRule : lbNatRules) {
                    Assert.assertTrue((lbNatRule.frontendPort() >= 5000 && lbNatRule.frontendPort()<= 5099)
                            || (lbNatRule.frontendPort() >= 6000 && lbNatRule.frontendPort()<= 6099));
                    Assert.assertTrue(lbNatRule.backendPort() == 22 || lbNatRule.backendPort() == 23);
                }
            }
        }
        Assert.assertTrue(nicCount > 0);

        // Validate other properties
        //
        Assert.assertEquals(virtualMachineScaleSet.vhdContainers().size(), 2);
        Assert.assertEquals(virtualMachineScaleSet.sku(), VirtualMachineScaleSetSkuTypes.STANDARD_A0);
        // Check defaults
        Assert.assertTrue(virtualMachineScaleSet.upgradeModel() == UpgradeMode.AUTOMATIC);
        Assert.assertEquals(virtualMachineScaleSet.capacity(), 2);
        // Fetch the primary Virtual network
        primaryNetwork = virtualMachineScaleSet.getPrimaryNetwork();

        String inboundNatPoolToRemove = null;
        for (String inboundNatPoolName :
                virtualMachineScaleSet.listPrimaryInternetFacingLoadBalancerInboundNatPools().keySet()) {
            inboundNatPoolToRemove = inboundNatPoolName;
            break;
        }

        LoadBalancer internalLoadBalancer = createInternalLoadBalancer(REGION,
                resourceGroup,
                primaryNetwork,
                "1");

        virtualMachineScaleSet
                .update()
                .withExistingPrimaryInternalLoadBalancer(internalLoadBalancer)
                .withoutPrimaryInternetFacingLoadBalancerNatPools(inboundNatPoolToRemove) // Remove one NatPool
                .apply();

        virtualMachineScaleSet = this.computeManager
                .virtualMachineScaleSets()
                .getByResourceGroup(RG_NAME, vmss_name);

        // Check LB after update
        //
        Assert.assertNotNull(virtualMachineScaleSet.getPrimaryInternetFacingLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternetFacingLoadBalancerBackends().size() == 2);
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternetFacingLoadBalancerInboundNatPools().size() == 1);

        Assert.assertNotNull(virtualMachineScaleSet.getPrimaryInternalLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternalLoadBalancerBackends().size() == 2);
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternalLoadBalancerInboundNatPools().size() == 2);

        // Check NIC + IpConfig after update
        //
        nics = virtualMachineScaleSet.listNetworkInterfaces();
        nicCount = 0;
        for (VirtualMachineScaleSetNetworkInterface nic : nics) {
            nicCount++;
            Map<String, VirtualMachineScaleSetNicIPConfiguration> ipConfigs =  nic.ipConfigurations();
            Assert.assertEquals(ipConfigs.size(), 1);
            for (Map.Entry<String, VirtualMachineScaleSetNicIPConfiguration> entry :ipConfigs.entrySet()) {
                VirtualMachineScaleSetNicIPConfiguration ipConfig = entry.getValue();
                Assert.assertNotNull(ipConfig);
                List<LoadBalancerBackend> lbBackends = ipConfig.listAssociatedLoadBalancerBackends();
                Assert.assertNotNull(lbBackends);
                // Updated VMSS has a internet facing LB with two backend pools and a internal LB with two
                // backend pools so there should be 4 backends in ip-config
                // #1: But this is not always happening, it seems update is really happening only
                // for subset of vms [TODO: Report this to network team]
                // Assert.True(lbBackends.Count == 4);
                // Assert.assertEquals(lbBackends.size(), 4);
                for (LoadBalancerBackend lbBackend : lbBackends) {
                    Map<String, LoadBalancingRule> lbRules = lbBackend.loadBalancingRules();
                    Assert.assertEquals(lbRules.size(), 1);
                    for (Map.Entry<String, LoadBalancingRule> ruleEntry : lbRules.entrySet()) {
                        LoadBalancingRule rule = ruleEntry.getValue();
                        Assert.assertNotNull(rule);
                        Assert.assertTrue((rule.frontendPort() == 80 && rule.backendPort() == 80)
                                || (rule.frontendPort() == 443 && rule.backendPort() == 443)
                                || (rule.frontendPort() == 1000 && rule.backendPort() == 1000)
                                || (rule.frontendPort() == 1001 && rule.backendPort() == 1001));
                    }
                }
                List<LoadBalancerInboundNatRule> lbNatRules = ipConfig.listAssociatedLoadBalancerInboundNatRules();
                // Updated VMSS has a internet facing LB with one nat pool and a internal LB with two
                // nat pools so there should be 3 nat rule in ip-config
                // Same issue as above #1
                // But this is not always happening, it seems update is really happening only
                // for subset of vms [TODO: Report this to network team]
                // Assert.assertEquals(lbNatRules.size(), 3);
                for (LoadBalancerInboundNatRule lbNatRule : lbNatRules) {
                    // As mentioned above some chnages are not propgating to all VM instances 6000+ should be there
                    Assert.assertTrue((lbNatRule.frontendPort() >= 6000 && lbNatRule.frontendPort()<= 6099)
                            || (lbNatRule.frontendPort() >= 5000 && lbNatRule.frontendPort()<= 5099)
                            || (lbNatRule.frontendPort() >= 8000 && lbNatRule.frontendPort()<= 8099)
                            || (lbNatRule.frontendPort() >= 9000 && lbNatRule.frontendPort()<= 9099));
                    // Same as above
                    Assert.assertTrue(lbNatRule.backendPort() == 23
                            || lbNatRule.backendPort() == 22
                            || lbNatRule.backendPort() == 44
                            || lbNatRule.backendPort() == 45);
                }
            }
        }
        Assert.assertTrue(nicCount > 0);
    }

    @Test
    public void canCreateTwoRegionalVirtualMachineScaleSetsAndAssociateEachWithDifferentBackendPoolOfZoneResilientLoadBalancer() throws Exception {
        // Zone resilient resource -> resources deployed in all zones by the service and it will be served by all AZs all the time.
        // ZoneResilientLoadBalancer -> STANDARD LB -> [Since service deploy them to all zones, user don't have to set zone explicitly, even if he does its a constrain as user can set only one zone at this time]

        Region REGION2 = Region.US_EAST2;

        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION2)
                .create();

        Network network = this.networkManager
                .networks()
                .define("vmssvnet")
                .withRegion(REGION2)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();

        // Creates a STANDARD LB with one public frontend ip configuration with two backend pools
        // Each address pool of STANDARD LB can hold different VMSS resource.
        //
        LoadBalancer publicLoadBalancer = createInternetFacingLoadBalancer(REGION2,
                resourceGroup,
                "1",
                LoadBalancerSkuType.STANDARD);

        // With default LB SKU BASIC, an attempt to associate two different VMSS to different
        // backend pool will cause below error (more accurately, while trying to put second VMSS)
        // {
        //        "startTime": "2017-09-06T14:27:22.1849435+00:00",
        //        "endTime": "2017-09-06T14:27:45.8885142+00:00",
        //        "status": "Failed",
        //        "error": {
        //            "code": "VmIsNotInSameAvailabilitySetAsLb",
        //            "message": "Virtual Machine /subscriptions/<sub-id>/resourceGroups/<rg-name>/providers/Microsoft.Compute/virtualMachines/|providers|Microsoft.Compute|virtualMachineScaleSets|<vm-ss-name>|virtualMachines|<instance-id> is using different Availability Set than other Virtual Machines connected to the Load Balancer(s) <lb-name>."
        //         },
        //        "name": "97531d64-db37-4d21-a1cb-9c53aad7c342"
        // }

        List<String> backends = new ArrayList<>();
        for (String backend : publicLoadBalancer.backends().keySet()) {
            backends.add(backend);
        }
        Assert.assertTrue(backends.size() == 2);

        List<String> natpools = new ArrayList<>();
        for (String natPool : publicLoadBalancer.inboundNatPools().keySet()) {
            natpools.add(natPool);
        }
        Assert.assertTrue(natpools.size() == 2);

        final String vmss_name1 = generateRandomResourceName("vmss1", 10);
        // HTTP goes to this virtual machine scale set
        //
        VirtualMachineScaleSet virtualMachineScaleSet1 = this.computeManager.virtualMachineScaleSets()
                .define(vmss_name1)
                .withRegion(REGION2)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_A0)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withExistingPrimaryInternetFacingLoadBalancer(publicLoadBalancer)
                .withPrimaryInternetFacingLoadBalancerBackends(backends.get(0)) // This VMSS in the first backend pool
                .withPrimaryInternetFacingLoadBalancerInboundNatPools(natpools.get(0))
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername("jvuser")
                .withRootPassword("123OData!@#123")
                .create();

        final String vmss_name2 = generateRandomResourceName("vmss2", 10);
        // HTTPS goes to this virtual machine scale set
        //
        VirtualMachineScaleSet virtualMachineScaleSet2 = this.computeManager.virtualMachineScaleSets()
                .define(vmss_name2)
                .withRegion(REGION2)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_A0)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withExistingPrimaryInternetFacingLoadBalancer(publicLoadBalancer)
                .withPrimaryInternetFacingLoadBalancerBackends(backends.get(1)) // This VMSS in the second backend pool
                .withPrimaryInternetFacingLoadBalancerInboundNatPools(natpools.get(1))
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername("jvuser")
                .withRootPassword("123OData!@#123")
                .create();

        // Validate Network specific properties (LB, VNet, NIC, IPConfig etc..)
        //
        Assert.assertNull(virtualMachineScaleSet1.getPrimaryInternalLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet1.listPrimaryInternalLoadBalancerBackends().size() == 0);
        Assert.assertTrue(virtualMachineScaleSet1.listPrimaryInternalLoadBalancerInboundNatPools().size() == 0);

        Assert.assertNotNull(virtualMachineScaleSet1.getPrimaryInternetFacingLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet1.listPrimaryInternetFacingLoadBalancerBackends().size() == 1);


        Assert.assertNull(virtualMachineScaleSet2.getPrimaryInternalLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet2.listPrimaryInternalLoadBalancerBackends().size() == 0);
        Assert.assertTrue(virtualMachineScaleSet2.listPrimaryInternalLoadBalancerInboundNatPools().size() == 0);

        Assert.assertNotNull(virtualMachineScaleSet2.getPrimaryInternetFacingLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet2.listPrimaryInternetFacingLoadBalancerBackends().size() == 1);
    }

    @Test
    public void canCreateZoneRedundantVirtualMachineScaleSetWithZoneResilientLoadBalancer() throws Exception {
        // Zone redundant VMSS is the one with multiple zones
        //

        Region REGION2 = Region.US_EAST2;

        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION2)
                .create();

        Network network = this.networkManager
                .networks()
                .define("vmssvnet")
                .withRegion(REGION2)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();

        // Zone redundant VMSS requires STANDARD LB
        //
        // Creates a STANDARD LB with one public frontend ip configuration with two backend pools
        // Each address pool of STANDARD LB can hold different VMSS resource.
        //
        LoadBalancer publicLoadBalancer = createInternetFacingLoadBalancer(REGION2,
                resourceGroup,
                "1",
                LoadBalancerSkuType.STANDARD);

        List<String> backends = new ArrayList<>();
        for (String backend : publicLoadBalancer.backends().keySet()) {
            backends.add(backend);
        }
        Assert.assertTrue(backends.size() == 2);

        final String vmss_name = generateRandomResourceName("vmss", 10);
        // HTTP & HTTPS traffic on port 80, 443 of Internet-facing LB goes to corresponding port in virtual machine scale set
        //
        VirtualMachineScaleSet virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets()
                .define(vmss_name)
                .withRegion(REGION2)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_D3_V2)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withExistingPrimaryInternetFacingLoadBalancer(publicLoadBalancer)
                .withPrimaryInternetFacingLoadBalancerBackends(backends.get(0), backends.get(1))
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername("jvuser")
                .withRootPassword("123OData!@#123")
                .withAvailabilityZone(AvailabilityZoneId.ZONE_1)  // Zone redundant - zone 1 + zone 2
                .withAvailabilityZone(AvailabilityZoneId.ZONE_2)
                .create();

        // Check zones
        //
        Assert.assertNotNull(virtualMachineScaleSet.availabilityZones());
        Assert.assertEquals(2, virtualMachineScaleSet.availabilityZones().size());

        // Validate Network specific properties (LB, VNet, NIC, IPConfig etc..)
        //
        Assert.assertNull(virtualMachineScaleSet.getPrimaryInternalLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternalLoadBalancerBackends().size() == 0);
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternalLoadBalancerInboundNatPools().size() == 0);

        Assert.assertNotNull(virtualMachineScaleSet.getPrimaryInternetFacingLoadBalancer());
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternetFacingLoadBalancerBackends().size() == 2);
        Assert.assertTrue(virtualMachineScaleSet.listPrimaryInternetFacingLoadBalancerInboundNatPools().size() == 2);

        Network primaryNetwork = virtualMachineScaleSet.getPrimaryNetwork();
        Assert.assertNotNull(primaryNetwork.id());
    }

    @Test
    public void canEnableMSIOnVirtualMachineScaleSetWithoutRoleAssignment() throws Exception {
        final String vmss_name = generateRandomResourceName("vmss", 10);
        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION)
                .create();

        Network network = this.networkManager
                .networks()
                .define("vmssvnet")
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();

        LoadBalancer publicLoadBalancer = createInternetFacingLoadBalancer(REGION,
                resourceGroup,
                "1",
                LoadBalancerSkuType.BASIC);
        List<String> backends = new ArrayList<>();
        for (String backend : publicLoadBalancer.backends().keySet()) {
            backends.add(backend);
        }
        Assert.assertTrue(backends.size() == 2);

        VirtualMachineScaleSet virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets()
                .define(vmss_name)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_A0)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withExistingPrimaryInternetFacingLoadBalancer(publicLoadBalancer)
                .withPrimaryInternetFacingLoadBalancerBackends(backends.get(0), backends.get(1))
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername("jvuser")
                .withRootPassword("123OData!@#123")
                .withSystemAssignedManagedServiceIdentity()
                .create();

        // Validate service created service principal
        //
        // TODO: Renable the below code snippet: https://github.com/Azure/azure-libraries-for-net/issues/739

        //        ServicePrincipal servicePrincipal = rbacManager
        //                .servicePrincipals()
        //                .getById(virtualMachineScaleSet.systemAssignedManagedServiceIdentityPrincipalId());
        //
        //        Assert.assertNotNull(servicePrincipal);
        //        Assert.assertNotNull(servicePrincipal.inner());

        // Ensure role assigned for resource group
        //
        PagedList<RoleAssignment> rgRoleAssignments = rbacManager.roleAssignments().listByScope(resourceGroup.id());
        Assert.assertNotNull(rgRoleAssignments);
        boolean found = false;
        for (RoleAssignment roleAssignment : rgRoleAssignments) {
            if (roleAssignment.principalId() != null && roleAssignment.principalId().equalsIgnoreCase(virtualMachineScaleSet.systemAssignedManagedServiceIdentityPrincipalId())) {
                found = true;
                break;
            }
        }
        Assert.assertFalse("Resource group should not have a role assignment with virtual machine scale set MSI principal", found);
    }

    @Test
    public void canEnableMSIOnVirtualMachineScaleSetWithMultipleRoleAssignment() throws Exception {
        final String vmss_name = generateRandomResourceName("vmss", 10);
        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION)
                .create();

        Network network = this.networkManager
                .networks()
                .define("vmssvnet")
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();

        LoadBalancer publicLoadBalancer = createInternetFacingLoadBalancer(REGION,
                resourceGroup,
                "1",
                LoadBalancerSkuType.BASIC);
        List<String> backends = new ArrayList<>();
        for (String backend : publicLoadBalancer.backends().keySet()) {
            backends.add(backend);
        }
        Assert.assertTrue(backends.size() == 2);

        StorageAccount storageAccount = this.storageManager.storageAccounts()
                .define(generateRandomResourceName("jvcsrg", 10))
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .create();

        VirtualMachineScaleSet virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets()
                .define(vmss_name)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_A0)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withExistingPrimaryInternetFacingLoadBalancer(publicLoadBalancer)
                .withPrimaryInternetFacingLoadBalancerBackends(backends.get(0), backends.get(1))
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername("jvuser")
                .withRootPassword("123OData!@#123")
                .withSystemAssignedManagedServiceIdentity()
                .withSystemAssignedIdentityBasedAccessToCurrentResourceGroup(BuiltInRole.CONTRIBUTOR)
                .withSystemAssignedIdentityBasedAccessTo(storageAccount.id(), BuiltInRole.CONTRIBUTOR)
                .create();

        Assert.assertNotNull(virtualMachineScaleSet.managedServiceIdentityType());
        Assert.assertTrue(virtualMachineScaleSet.managedServiceIdentityType().equals(ResourceIdentityType.SYSTEM_ASSIGNED));

        // Validate service created service principal
        //
        // TODO: Renable the below code snippet: https://github.com/Azure/azure-libraries-for-net/issues/739

        //        ServicePrincipal servicePrincipal = rbacManager
        //                .servicePrincipals()
        //                .getById(virtualMachineScaleSet.systemAssignedManagedServiceIdentityPrincipalId());
        //
        //        Assert.assertNotNull(servicePrincipal);
        //        Assert.assertNotNull(servicePrincipal.inner());

        // Ensure role assigned for resource group
        //
        PagedList<RoleAssignment> rgRoleAssignments = rbacManager.roleAssignments().listByScope(resourceGroup.id());
        Assert.assertNotNull(rgRoleAssignments);
        boolean found = false;
        for (RoleAssignment roleAssignment : rgRoleAssignments) {
            if (roleAssignment.principalId() != null && roleAssignment.principalId().equalsIgnoreCase(virtualMachineScaleSet.systemAssignedManagedServiceIdentityPrincipalId())) {
                found = true;
                break;
            }
        }
        Assert.assertTrue("Resource group should have a role assignment with virtual machine scale set MSI principal", found);

        // Ensure role assigned for storage account
        //
        PagedList<RoleAssignment> stgRoleAssignments = rbacManager.roleAssignments().listByScope(storageAccount.id());
        Assert.assertNotNull(stgRoleAssignments);
        found = false;
        for (RoleAssignment roleAssignment : stgRoleAssignments) {
            if (roleAssignment.principalId() != null && roleAssignment.principalId().equalsIgnoreCase(virtualMachineScaleSet.systemAssignedManagedServiceIdentityPrincipalId())) {
                found = true;
                break;
            }
        }
        Assert.assertTrue("Storage account should have a role assignment with virtual machine scale set MSI principal", found);
    }

    @Test
    public void canGetSingleVMSSInstance() throws Exception {

        final String vmss_name = generateRandomResourceName("vmss", 10);
        ResourceGroup resourceGroup = this.resourceManager.resourceGroups()
                .define(RG_NAME)
                .withRegion(REGION)
                .create();

        Network network = this.networkManager
                .networks()
                .define("vmssvnet")
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withAddressSpace("10.0.0.0/28")
                .withSubnet("subnet1", "10.0.0.0/28")
                .create();


        LoadBalancer publicLoadBalancer = createInternetFacingLoadBalancer(REGION,
                resourceGroup,
                "1",
                LoadBalancerSkuType.BASIC);

        List<String> backends = new ArrayList<>();
        for (String backend : publicLoadBalancer.backends().keySet()) {
            backends.add(backend);
        }
        Assert.assertTrue(backends.size() == 2);

        VirtualMachineScaleSet virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets()
                .define(vmss_name)
                .withRegion(REGION)
                .withExistingResourceGroup(resourceGroup)
                .withSku(VirtualMachineScaleSetSkuTypes.STANDARD_A0)
                .withExistingPrimaryNetworkSubnet(network, "subnet1")
                .withExistingPrimaryInternetFacingLoadBalancer(publicLoadBalancer)
                .withPrimaryInternetFacingLoadBalancerBackends(backends.get(0), backends.get(1))
                .withoutPrimaryInternalLoadBalancer()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername("jvuser")
                .withRootPassword("123OData!@#123")
                .withNewStorageAccount(generateRandomResourceName("stg", 15))
                .withNewStorageAccount(generateRandomResourceName("stg3", 15))
                .withUpgradeMode(UpgradeMode.MANUAL)
                .create();

        virtualMachineScaleSet = this.computeManager.virtualMachineScaleSets().getByResourceGroup(RG_NAME, vmss_name);
        VirtualMachineScaleSetVMs virtualMachineScaleSetVMs = virtualMachineScaleSet.virtualMachines();
        VirtualMachineScaleSetVM firstVm = virtualMachineScaleSetVMs.list().get(0);
        VirtualMachineScaleSetVM fetchedVm = virtualMachineScaleSetVMs.getInstance(firstVm.instanceId());
        this.checkVmsEqual(firstVm, fetchedVm);
        VirtualMachineScaleSetVM fetchedAsyncVm = virtualMachineScaleSetVMs.getInstanceAsync(firstVm.instanceId()).toBlocking().single();
        this.checkVmsEqual(firstVm, fetchedAsyncVm);
    }

    private void checkVmsEqual(VirtualMachineScaleSetVM original, VirtualMachineScaleSetVM fetched)
    {
        Assert.assertEquals(original.administratorUserName(), fetched.administratorUserName());
        Assert.assertEquals(original.availabilitySetId(), fetched.availabilitySetId());
        Assert.assertEquals(original.bootDiagnosticEnabled(), fetched.bootDiagnosticEnabled());
        Assert.assertEquals(original.bootDiagnosticStorageAccountUri(), fetched.bootDiagnosticStorageAccountUri());
        Assert.assertEquals(original.computerName(), fetched.computerName());
        Assert.assertEquals(original.dataDisks().size(), fetched.dataDisks().size());
        Assert.assertEquals(original.extensions().size(), fetched.extensions().size());
        Assert.assertEquals(original.instanceId(), fetched.instanceId());
        Assert.assertEquals(original.isLatestScaleSetUpdateApplied(), fetched.isLatestScaleSetUpdateApplied());
        Assert.assertEquals(original.isLinuxPasswordAuthenticationEnabled(), fetched.isLatestScaleSetUpdateApplied());
        Assert.assertEquals(original.isManagedDiskEnabled(), fetched.isManagedDiskEnabled());
        Assert.assertEquals(original.isOSBasedOnCustomImage(), fetched.isOSBasedOnCustomImage());
        Assert.assertEquals(original.isOSBasedOnPlatformImage(), fetched.isOSBasedOnPlatformImage());
        Assert.assertEquals(original.isOSBasedOnStoredImage(), fetched.isOSBasedOnStoredImage());
        Assert.assertEquals(original.isWindowsAutoUpdateEnabled(), fetched.isWindowsAutoUpdateEnabled());
        Assert.assertEquals(original.isWindowsVMAgentProvisioned(), original.isWindowsVMAgentProvisioned());
        Assert.assertEquals(original.networkInterfaceIds().size(), fetched.networkInterfaceIds().size());
        Assert.assertEquals(original.osDiskCachingType(), fetched.osDiskCachingType());
        Assert.assertEquals(original.osDiskId(), fetched.osDiskId());
        Assert.assertEquals(original.osDiskName(), fetched.osDiskName());
        Assert.assertEquals(original.osDiskSizeInGB(), fetched.osDiskSizeInGB());
        Assert.assertEquals(original.osType(), fetched.osType());
        Assert.assertEquals(original.osUnmanagedDiskVhdUri(), fetched.osUnmanagedDiskVhdUri());
        Assert.assertEquals(original.powerState(), fetched.powerState());
        Assert.assertEquals(original.primaryNetworkInterfaceId(), fetched.primaryNetworkInterfaceId());
        Assert.assertEquals(original.size(), fetched.size());
        Assert.assertEquals(original.sku().name(), fetched.sku().name());
        Assert.assertEquals(original.storedImageUnmanagedVhdUri(), fetched.storedImageUnmanagedVhdUri());
        Assert.assertEquals(original.unmanagedDataDisks().size(), fetched.unmanagedDataDisks().size());
        Assert.assertEquals(original.windowsTimeZone(), fetched.windowsTimeZone());
    }


    private void checkVMInstances(VirtualMachineScaleSet vmScaleSet) {
        VirtualMachineScaleSetVMs virtualMachineScaleSetVMs = vmScaleSet.virtualMachines();
        PagedList<VirtualMachineScaleSetVM> virtualMachines = virtualMachineScaleSetVMs.list();

        Assert.assertEquals(virtualMachines.size(), vmScaleSet.capacity());
        Assert.assertTrue(virtualMachines.size() > 0);
        virtualMachineScaleSetVMs.updateInstances(virtualMachines.get(0).instanceId());

        for (VirtualMachineScaleSetVM vm : virtualMachines) {
            Assert.assertNotNull(vm.size());
            Assert.assertEquals(vm.osType(), OperatingSystemTypes.LINUX);
            Assert.assertNotNull(vm.computerName().startsWith(vmScaleSet.computerNamePrefix()));
            Assert.assertTrue(vm.isLinuxPasswordAuthenticationEnabled());
            Assert.assertTrue(vm.isOSBasedOnPlatformImage());
            Assert.assertNull(vm.osDiskId());                   // VMSS is un-managed, so osDiskId must be null
            Assert.assertNotNull(vm.osUnmanagedDiskVhdUri());   // VMSS is un-managed, so osVhd should not be null
            Assert.assertNull(vm.storedImageUnmanagedVhdUri());
            Assert.assertFalse(vm.isWindowsAutoUpdateEnabled());
            Assert.assertFalse(vm.isWindowsVMAgentProvisioned());
            Assert.assertTrue(vm.administratorUserName().equalsIgnoreCase("jvuser"));
            VirtualMachineImage vmImage = vm.getOSPlatformImage();
            Assert.assertNotNull(vmImage);
            Assert.assertEquals(vm.extensions().size(), vmScaleSet.extensions().size());
            Assert.assertNotNull(vm.powerState());
            vm.refreshInstanceView();
        }

        // Check actions
        VirtualMachineScaleSetVM virtualMachineScaleSetVM = virtualMachines.get(0);
        Assert.assertNotNull(virtualMachineScaleSetVM);
        virtualMachineScaleSetVM.restart();
        virtualMachineScaleSetVM.powerOff();
        virtualMachineScaleSetVM.refreshInstanceView();
        Assert.assertEquals(virtualMachineScaleSetVM.powerState(), PowerState.STOPPED);
        virtualMachineScaleSetVM.start();

        // Check Instance NICs
        //
        for (VirtualMachineScaleSetVM vm : virtualMachines) {
            PagedList<VirtualMachineScaleSetNetworkInterface> nics
                    = vmScaleSet.listNetworkInterfacesByInstanceId(vm.instanceId());
            Assert.assertNotNull(nics);
            Assert.assertEquals(nics.size(), 1);
            VirtualMachineScaleSetNetworkInterface nic = nics.get(0);
            Assert.assertNotNull(nic.virtualMachineId());
            Assert.assertTrue(nic.virtualMachineId().toLowerCase().equalsIgnoreCase(vm.id()));
            Assert.assertNotNull(vm.listNetworkInterfaces());
            VirtualMachineScaleSetNetworkInterface nicA = vmScaleSet.getNetworkInterfaceByInstanceId(vm.instanceId(), nic.name());
            Assert.assertNotNull(nicA);
            VirtualMachineScaleSetNetworkInterface nicB = vm.getNetworkInterface(nic.name());
            Assert.assertNotNull(nicB);
        }
    }

    @Test
    public void testVirtualMachineScaleSetSkuTypes() {
        RG_NAME = null;
        VirtualMachineScaleSetSkuTypes skuType = VirtualMachineScaleSetSkuTypes.STANDARD_A0;
        Assert.assertNull(skuType.sku().capacity());
        // first copy of sku
        Sku sku1 = skuType.sku();
        Assert.assertNull(sku1.capacity());
        sku1.withCapacity(new Long(1));
        Assert.assertEquals(sku1.capacity().longValue(), 1);
        // Ensure the original is not affected
        Assert.assertNull(skuType.sku().capacity());
        // second copy of sku
        Sku sku2 = skuType.sku();
        Assert.assertNull(sku2.capacity());
        sku2.withCapacity(new Long(2));
        Assert.assertEquals(sku2.capacity().longValue(), 2);
        // Ensure the original is not affected
        Assert.assertNull(skuType.sku().capacity());
        // Ensure previous copy is not affected due to change in first copy
        Assert.assertEquals(sku1.capacity().longValue(), 1);
    }
}