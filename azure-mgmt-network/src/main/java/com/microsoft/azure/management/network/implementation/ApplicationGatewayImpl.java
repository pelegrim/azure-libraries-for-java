/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.azure.management.network.implementation;

import com.microsoft.azure.management.network.ApplicationGateway;
import com.microsoft.azure.management.network.ApplicationGatewayAuthenticationCertificate;
import com.microsoft.azure.management.network.ApplicationGatewayAutoscaleConfiguration;
import com.microsoft.azure.management.network.ApplicationGatewayBackend;
import com.microsoft.azure.management.network.ApplicationGatewayBackendAddressPool;
import com.microsoft.azure.management.network.ApplicationGatewayBackendHealth;
import com.microsoft.azure.management.network.ApplicationGatewayBackendHealthPool;
import com.microsoft.azure.management.network.ApplicationGatewayBackendHttpConfiguration;
import com.microsoft.azure.management.network.ApplicationGatewayBackendHttpSettings;
import com.microsoft.azure.management.network.ApplicationGatewayFirewallMode;
import com.microsoft.azure.management.network.ApplicationGatewayFrontend;
import com.microsoft.azure.management.network.ApplicationGatewayFrontendPort;
import com.microsoft.azure.management.network.ApplicationGatewayHttpListener;
import com.microsoft.azure.management.network.ApplicationGatewayListener;
import com.microsoft.azure.management.network.ApplicationGatewayIPConfiguration;
import com.microsoft.azure.management.network.ApplicationGatewayFrontendIPConfiguration;
import com.microsoft.azure.management.network.ApplicationGatewayOperationalState;
import com.microsoft.azure.management.network.ApplicationGatewayProbe;
import com.microsoft.azure.management.network.ApplicationGatewayRedirectConfiguration;
import com.microsoft.azure.management.network.ApplicationGatewayRequestRoutingRule;
import com.microsoft.azure.management.network.ApplicationGatewayRequestRoutingRuleType;
import com.microsoft.azure.management.network.ApplicationGatewaySku;
import com.microsoft.azure.management.network.ApplicationGatewaySkuName;
import com.microsoft.azure.management.network.ApplicationGatewaySslCertificate;
import com.microsoft.azure.management.network.ApplicationGatewaySslPolicy;
import com.microsoft.azure.management.network.ApplicationGatewaySslProtocol;
import com.microsoft.azure.management.network.ApplicationGatewayTier;
import com.microsoft.azure.management.network.ApplicationGatewayUrlPathMap;
import com.microsoft.azure.management.network.ApplicationGatewayWebApplicationFirewallConfiguration;
import com.microsoft.azure.management.network.IPAllocationMethod;
import com.microsoft.azure.management.network.Network;
import com.microsoft.azure.management.network.PublicIPAddress;
import com.microsoft.azure.management.network.Subnet;
import com.microsoft.azure.management.network.model.GroupableParentResourceWithTagsImpl;
import com.microsoft.azure.management.resources.fluentcore.arm.AvailabilityZoneId;
import com.microsoft.azure.management.resources.fluentcore.arm.ResourceUtils;
import com.microsoft.azure.management.resources.fluentcore.arm.models.Resource;
import com.microsoft.azure.management.resources.fluentcore.model.Creatable;
import com.microsoft.azure.management.resources.fluentcore.utils.SdkContext;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.TreeSet;

import com.microsoft.azure.SubResource;
import com.microsoft.azure.management.apigeneration.LangDefinition;

import com.microsoft.azure.management.resources.fluentcore.utils.Utils;

import rx.Completable;
import rx.Observable;
import rx.functions.Func1;

/**
 * Implementation of the ApplicationGateway interface.
 */
@LangDefinition
class ApplicationGatewayImpl
    extends GroupableParentResourceWithTagsImpl<
        ApplicationGateway,
        ApplicationGatewayInner,
        ApplicationGatewayImpl,
        NetworkManager>
    implements
        ApplicationGateway,
        ApplicationGateway.Definition,
        ApplicationGateway.Update {

    private Map<String, ApplicationGatewayIPConfiguration> ipConfigs;
    private Map<String, ApplicationGatewayFrontend> frontends;
    private Map<String, ApplicationGatewayProbe> probes;
    private Map<String, ApplicationGatewayBackend> backends;
    private Map<String, ApplicationGatewayBackendHttpConfiguration> backendConfigs;
    private Map<String, ApplicationGatewayListener> listeners;
    private Map<String, ApplicationGatewayRequestRoutingRule> rules;
    private Map<String, ApplicationGatewaySslCertificate> sslCerts;
    private Map<String, ApplicationGatewayAuthenticationCertificate> authCertificates;
    private Map<String, ApplicationGatewayRedirectConfiguration> redirectConfigs;
    private Map<String, ApplicationGatewayUrlPathMap> urlPathMaps;

    private static final String DEFAULT = "default";
    private ApplicationGatewayFrontendImpl defaultPrivateFrontend;
    private ApplicationGatewayFrontendImpl defaultPublicFrontend;

    private Map<String, String> creatablePipsByFrontend;

    ApplicationGatewayImpl(
            String name,
            final ApplicationGatewayInner innerModel,
            final NetworkManager networkManager) {
        super(name, innerModel, networkManager);
    }

    // Verbs

    @Override
    public Observable<ApplicationGateway> refreshAsync() {
        return super.refreshAsync().map(new Func1<ApplicationGateway, ApplicationGateway>() {
            @Override
            public ApplicationGateway call(ApplicationGateway applicationGateway) {
                ApplicationGatewayImpl impl = (ApplicationGatewayImpl) applicationGateway;
                impl.initializeChildrenFromInner();
                return impl;
            }
        });
    }

    @Override
    protected Observable<ApplicationGatewayInner> getInnerAsync() {
        return this.manager().inner().applicationGateways().getByResourceGroupAsync(this.resourceGroupName(), this.name());
    }

    @Override
    protected Observable<ApplicationGatewayInner> applyTagsToInnerAsync() {
        return this.manager().inner().applicationGateways().updateTagsAsync(resourceGroupName(), name(), inner().getTags());
    }

    // Helpers

    @Override
    protected void initializeChildrenFromInner() {
        initializeConfigsFromInner();
        initializeFrontendsFromInner();
        initializeProbesFromInner();
        initializeBackendsFromInner();
        initializeBackendHttpConfigsFromInner();
        initializeHttpListenersFromInner();
        initializeRedirectConfigurationsFromInner();
        initializeRequestRoutingRulesFromInner();
        initializeSslCertificatesFromInner();
        initializeAuthCertificatesFromInner();
        initializeUrlPathMapsFromInner();
        this.defaultPrivateFrontend = null;
        this.defaultPublicFrontend = null;
        this.creatablePipsByFrontend = new HashMap<>();
    }

    private void initializeAuthCertificatesFromInner() {
        this.authCertificates = new TreeMap<>();
        List<ApplicationGatewayAuthenticationCertificateInner> inners = this.inner().authenticationCertificates();
        if (inners != null) {
            for (ApplicationGatewayAuthenticationCertificateInner inner : inners) {
                ApplicationGatewayAuthenticationCertificateImpl cert = new ApplicationGatewayAuthenticationCertificateImpl(inner, this);
                this.authCertificates.put(inner.name(), cert);
            }
        }
    }

    private void initializeSslCertificatesFromInner() {
        this.sslCerts = new TreeMap<>();
        List<ApplicationGatewaySslCertificateInner> inners = this.inner().sslCertificates();
        if (inners != null) {
            for (ApplicationGatewaySslCertificateInner inner : inners) {
                ApplicationGatewaySslCertificateImpl cert = new ApplicationGatewaySslCertificateImpl(inner, this);
                this.sslCerts.put(inner.name(), cert);
            }
        }
    }

    private void initializeFrontendsFromInner() {
        this.frontends = new TreeMap<>();
        List<ApplicationGatewayFrontendIPConfiguration> inners = this.inner().frontendIPConfigurations();
        if (inners != null) {
            for (ApplicationGatewayFrontendIPConfiguration inner : inners) {
                ApplicationGatewayFrontendImpl frontend = new ApplicationGatewayFrontendImpl(inner, this);
                this.frontends.put(inner.name(), frontend);
            }
        }
    }

    private void initializeProbesFromInner() {
        this.probes = new TreeMap<>();
        List<ApplicationGatewayProbeInner> inners = this.inner().probes();
        if (inners != null) {
            for (ApplicationGatewayProbeInner inner : inners) {
                ApplicationGatewayProbeImpl probe = new ApplicationGatewayProbeImpl(inner, this);
                this.probes.put(inner.name(), probe);
            }
        }
    }

    private void initializeBackendsFromInner() {
        this.backends = new TreeMap<>();
        List<ApplicationGatewayBackendAddressPool> inners = this.inner().backendAddressPools();
        if (inners != null) {
            for (ApplicationGatewayBackendAddressPool inner : inners) {
                ApplicationGatewayBackendImpl backend = new ApplicationGatewayBackendImpl(inner, this);
                this.backends.put(inner.name(), backend);
            }
        }
    }

    private void initializeBackendHttpConfigsFromInner() {
        this.backendConfigs = new TreeMap<>();
        List<ApplicationGatewayBackendHttpSettings> inners = this.inner().backendHttpSettingsCollection();
        if (inners != null) {
            for (ApplicationGatewayBackendHttpSettings inner : inners) {
                ApplicationGatewayBackendHttpConfigurationImpl httpConfig = new ApplicationGatewayBackendHttpConfigurationImpl(inner, this);
                this.backendConfigs.put(inner.name(), httpConfig);
            }
        }
    }

    private void initializeHttpListenersFromInner() {
        this.listeners = new TreeMap<>();
        List<ApplicationGatewayHttpListener> inners = this.inner().httpListeners();
        if (inners != null) {
            for (ApplicationGatewayHttpListener inner : inners) {
                ApplicationGatewayListenerImpl httpListener = new ApplicationGatewayListenerImpl(inner, this);
                this.listeners.put(inner.name(), httpListener);
            }
        }
    }

    private void initializeRedirectConfigurationsFromInner() {
        this.redirectConfigs = new TreeMap<>();
        List<ApplicationGatewayRedirectConfigurationInner> inners = this.inner().redirectConfigurations();
        if (inners != null) {
            for (ApplicationGatewayRedirectConfigurationInner inner : inners) {
                ApplicationGatewayRedirectConfigurationImpl redirectConfig = new ApplicationGatewayRedirectConfigurationImpl(inner, this);
                this.redirectConfigs.put(inner.name(), redirectConfig);
            }
        }
    }

    private void initializeUrlPathMapsFromInner() {
        this.urlPathMaps = new TreeMap<>();
        List<ApplicationGatewayUrlPathMapInner> inners = this.inner().urlPathMaps();
        if (inners != null) {
            for (ApplicationGatewayUrlPathMapInner inner : inners) {
                ApplicationGatewayUrlPathMapImpl wrapper = new ApplicationGatewayUrlPathMapImpl(inner, this);
                this.urlPathMaps.put(inner.name(), wrapper);
            }
        }
    }

    private void initializeRequestRoutingRulesFromInner() {
        this.rules = new TreeMap<>();
        List<ApplicationGatewayRequestRoutingRuleInner> inners = this.inner().requestRoutingRules();
        if (inners != null) {
            for (ApplicationGatewayRequestRoutingRuleInner inner : inners) {
                ApplicationGatewayRequestRoutingRuleImpl rule = new ApplicationGatewayRequestRoutingRuleImpl(inner, this);
                this.rules.put(inner.name(), rule);
            }
        }
    }

    private void initializeConfigsFromInner() {
        this.ipConfigs = new TreeMap<>();
        List<ApplicationGatewayIPConfigurationInner> inners = this.inner().gatewayIPConfigurations();
        if (inners != null) {
            for (ApplicationGatewayIPConfigurationInner inner : inners) {
                ApplicationGatewayIPConfigurationImpl config = new ApplicationGatewayIPConfigurationImpl(inner, this);
                this.ipConfigs.put(inner.name(), config);
            }
        }
    }

    @Override
    protected void beforeCreating()  {
        // Process created PIPs
        for (Entry<String, String> frontendPipPair : this.creatablePipsByFrontend.entrySet()) {
            Resource createdPip = this.<Resource>taskResult(frontendPipPair.getValue());
            this.updateFrontend(frontendPipPair.getKey()).withExistingPublicIPAddress(createdPip.id());
        }
        this.creatablePipsByFrontend.clear();

        // Reset and update IP configs
        ensureDefaultIPConfig();
        this.inner().withGatewayIPConfigurations(innersFromWrappers(this.ipConfigs.values()));

        // Reset and update frontends
        this.inner().withFrontendIPConfigurations(innersFromWrappers(this.frontends.values()));

        // Reset and update probes
        this.inner().withProbes(innersFromWrappers(this.probes.values()));

        // Reset and update auth certs
        this.inner().withAuthenticationCertificates(innersFromWrappers(this.authCertificates.values()));

        // Reset and update backends
        this.inner().withBackendAddressPools(innersFromWrappers(this.backends.values()));

        // Reset and update SSL certs
        this.inner().withSslCertificates(innersFromWrappers(this.sslCerts.values()));

        // Reset and update URL path maps
        this.inner().withUrlPathMaps(innersFromWrappers(this.urlPathMaps.values()));



        // Reset and update backend HTTP settings configs
        this.inner().withBackendHttpSettingsCollection(innersFromWrappers(this.backendConfigs.values()));
        for (ApplicationGatewayBackendHttpConfiguration config : this.backendConfigs.values()) {
            SubResource ref;

            // Clear deleted probe references
            ref = config.inner().probe();
            if (ref != null && !this.probes().containsKey(ResourceUtils.nameFromResourceId(ref.id()))) {
                config.inner().withProbe(null);
            }

            // Clear deleted auth cert references
            List<SubResource> certRefs = config.inner().authenticationCertificates();
            if (certRefs != null) {
                // Make a copy of the cert refs, because we will be deleting in place
                certRefs = new ArrayList<>(certRefs);
                for (SubResource certRef : certRefs) {
                    if (certRef != null && !this.authCertificates.containsKey(ResourceUtils.nameFromResourceId(certRef.id()))) {
                        config.inner().authenticationCertificates().remove(certRef);
                    }
                }
            }
        }

        // Reset and update redirect configurations
        this.inner().withRedirectConfigurations(innersFromWrappers(this.redirectConfigs.values()));
        for (ApplicationGatewayRedirectConfiguration redirect : this.redirectConfigs.values()) {
            SubResource ref;

            // Clear deleted listener references
            ref = redirect.inner().targetListener();
            if (ref != null && !this.listeners.containsKey(ResourceUtils.nameFromResourceId(ref.id()))) {
                redirect.inner().withTargetListener(null);
            }
        }

        // Reset and update HTTP listeners
        this.inner().withHttpListeners(innersFromWrappers(this.listeners.values()));
        for (ApplicationGatewayListener listener : this.listeners.values()) {
            SubResource ref;

            // Clear deleted frontend references
            ref = listener.inner().frontendIPConfiguration();
            if (ref != null && !this.frontends().containsKey(ResourceUtils.nameFromResourceId(ref.id()))) {
                listener.inner().withFrontendIPConfiguration(null);
            }

            // Clear deleted frontend port references
            ref = listener.inner().frontendPort();
            if (ref != null && !this.frontendPorts().containsKey(ResourceUtils.nameFromResourceId(ref.id()))) {
                listener.inner().withFrontendPort(null);
            }

            // Clear deleted SSL certificate references
            ref = listener.inner().sslCertificate();
            if (ref != null && !this.sslCertificates().containsKey(ResourceUtils.nameFromResourceId(ref.id()))) {
                listener.inner().withSslCertificate(null);
            }
        }

        // Reset and update request routing rules
        this.inner().withRequestRoutingRules(innersFromWrappers(this.rules.values()));
        for (ApplicationGatewayRequestRoutingRule rule : this.rules.values()) {
            SubResource ref;

            // Clear deleted redirect configs
            ref = rule.inner().redirectConfiguration();
            if (ref != null && !this.redirectConfigs.containsKey(ResourceUtils.nameFromResourceId(ref.id()))) {
                rule.inner().withRedirectConfiguration(null);
            }

            // Clear deleted backends
            ref = rule.inner().backendAddressPool();
            if (ref != null && !this.backends().containsKey(ResourceUtils.nameFromResourceId(ref.id()))) {
                rule.inner().withBackendAddressPool(null);
            }

            // Clear deleted backend HTTP configs
            ref = rule.inner().backendHttpSettings();
            if (ref != null && !this.backendConfigs.containsKey(ResourceUtils.nameFromResourceId(ref.id()))) {
                rule.inner().withBackendHttpSettings(null);
            }

            // Clear deleted frontend HTTP listeners
            ref = rule.inner().httpListener();
            if (ref != null && !this.listeners().containsKey(ResourceUtils.nameFromResourceId(ref.id()))) {
                rule.inner().withHttpListener(null);
            }
        }
    }

    @Override
    protected void afterCreating() {
    }

    protected SubResource ensureBackendRef(String name) {
        // Ensure existence of backend, creating one if needed
        ApplicationGatewayBackendImpl backend;
        if (name == null) {
            backend = this.ensureUniqueBackend();
        } else {
            backend = this.defineBackend(name);
            backend.attach();
        }

        // Return backend reference
        return new SubResource()
                .withId(this.futureResourceId() + "/backendAddressPools/" + backend.name());
    }

    protected ApplicationGatewayBackendImpl ensureUniqueBackend() {
        String name = SdkContext.randomResourceName("backend", 20);
        ApplicationGatewayBackendImpl backend = this.defineBackend(name);
        backend.attach();
        return backend;
    }

    private ApplicationGatewayIPConfigurationImpl ensureDefaultIPConfig() {
        ApplicationGatewayIPConfigurationImpl ipConfig = (ApplicationGatewayIPConfigurationImpl) defaultIPConfiguration();
        if (ipConfig == null) {
            String name = SdkContext.randomResourceName("ipcfg", 11);
            ipConfig = this.defineIPConfiguration(name);
            ipConfig.attach();
        }
        return ipConfig;
    }

    protected ApplicationGatewayFrontendImpl ensureDefaultPrivateFrontend() {
        ApplicationGatewayFrontendImpl frontend = (ApplicationGatewayFrontendImpl) defaultPrivateFrontend();
        if (frontend != null) {
            return frontend;
        } else {
            String name = SdkContext.randomResourceName("frontend", 14);
            frontend = this.defineFrontend(name);
            frontend.attach();
            this.defaultPrivateFrontend = frontend;
            return frontend;
        }
    }

    protected ApplicationGatewayFrontendImpl ensureDefaultPublicFrontend() {
        ApplicationGatewayFrontendImpl frontend = (ApplicationGatewayFrontendImpl) defaultPublicFrontend();
        if (frontend != null) {
            return frontend;
        } else {
            String name = SdkContext.randomResourceName("frontend", 14);
            frontend = this.defineFrontend(name);
            frontend.attach();
            this.defaultPublicFrontend = frontend;
            return frontend;
        }
    }

    private Creatable<Network> creatableNetwork = null;
    private Creatable<Network> ensureDefaultNetworkDefinition() {
        if (this.creatableNetwork == null) {
            final String vnetName = SdkContext.randomResourceName("vnet", 10);
            this.creatableNetwork = this.manager().networks().define(vnetName)
                    .withRegion(this.region())
                    .withExistingResourceGroup(this.resourceGroupName())
                    .withAddressSpace("10.0.0.0/24")
                    .withSubnet(DEFAULT, "10.0.0.0/25")
                    .withSubnet("apps", "10.0.0.128/25");
        }

        return this.creatableNetwork;
    }

    private Creatable<PublicIPAddress> creatablePip = null;
    private Creatable<PublicIPAddress> ensureDefaultPipDefinition() {
        if (this.creatablePip == null) {
            final String pipName = SdkContext.randomResourceName("pip", 9);
            this.creatablePip = this.manager().publicIPAddresses().define(pipName)
                    .withRegion(this.regionName())
                    .withExistingResourceGroup(this.resourceGroupName());
        }

        return this.creatablePip;
    }

    private static ApplicationGatewayFrontendImpl useSubnetFromIPConfigForFrontend(
            ApplicationGatewayIPConfigurationImpl ipConfig,
            ApplicationGatewayFrontendImpl frontend) {
        if (frontend != null) {
            frontend.withExistingSubnet(ipConfig.networkId(), ipConfig.subnetName());
            if (frontend.privateIPAddress() == null) {
                frontend.withPrivateIPAddressDynamic();
            } else if (frontend.privateIPAllocationMethod() == null) {
                frontend.withPrivateIPAddressDynamic();
            }
        }
        return frontend;
    }

    @Override
    protected Observable<ApplicationGatewayInner> createInner() {
        // Determine if a default public frontend PIP should be created
        final ApplicationGatewayFrontendImpl defaultPublicFrontend = (ApplicationGatewayFrontendImpl) defaultPublicFrontend();
        final Observable<Resource> pipObservable;
        if (defaultPublicFrontend != null && defaultPublicFrontend.publicIPAddressId() == null) {
            // If public frontend requested but no PIP specified, then create a default PIP
            pipObservable = Utils.<PublicIPAddress>rootResource(ensureDefaultPipDefinition()
                    .createAsync()).map(new Func1<PublicIPAddress, Resource>() {
                        @Override
                        public Resource call(PublicIPAddress publicIPAddress) {
                            defaultPublicFrontend.withExistingPublicIPAddress(publicIPAddress);
                            return publicIPAddress;
                        }
                    });
        } else {
            // If no public frontend requested, skip creating the PIP
            pipObservable = Observable.empty();
        }

        // Determine if default VNet should be created
        final ApplicationGatewayIPConfigurationImpl defaultIPConfig = ensureDefaultIPConfig();
        final ApplicationGatewayFrontendImpl defaultPrivateFrontend = (ApplicationGatewayFrontendImpl) defaultPrivateFrontend();
        final Observable<Resource> networkObservable;
        if (defaultIPConfig.subnetName() != null) {
            // If default IP config already has a subnet assigned to it...
            if (defaultPrivateFrontend != null) {
                // ...and a private frontend is requested, then use the same vnet for the private frontend
                useSubnetFromIPConfigForFrontend(defaultIPConfig, defaultPrivateFrontend);
            }
            // ...and no need to create a default VNet
            networkObservable = Observable.empty(); // ...and don't create another VNet
        } else {
            // But if default IP config does not have a subnet specified, then create a default VNet
            networkObservable = Utils.<Network>rootResource(ensureDefaultNetworkDefinition()
                .createAsync()).map(new Func1<Network, Resource>() {
                    @Override
                    public Resource call(Network network) {
                        //... and assign the created VNet to the default IP config
                        defaultIPConfig.withExistingSubnet(network, DEFAULT);
                        if (defaultPrivateFrontend != null) {
                            // If a private frontend is also requested, then use the same VNet for the private frontend as for the IP config
                            /* TODO: Not sure if the assumption of the same subnet for the frontend and the IP config will hold in
                             * the future, but the existing ARM template for App Gateway for some reason uses the same subnet for the
                             * IP config and the private frontend. Also, trying to use different subnets results in server error today saying they
                             * have to be the same. This may need to be revisited in the future however, as this is somewhat inconsistent
                             * with what the documentation says.
                             */
                            useSubnetFromIPConfigForFrontend(defaultIPConfig, defaultPrivateFrontend);
                        }
                        return network;
                    }
                });
        }

        final ApplicationGatewaysInner innerCollection = this.manager().inner().applicationGateways();
        return Observable.merge(networkObservable, pipObservable)
                .defaultIfEmpty(null)
                .last().flatMap(new Func1<Resource, Observable<ApplicationGatewayInner>>() {
                    @Override
                    public Observable<ApplicationGatewayInner> call(Resource resource) {
                        return innerCollection.createOrUpdateAsync(resourceGroupName(), name(), inner());
                    }
                });
    }

    /**
     * Determines whether the app gateway child that can be found using a name or a port number can be created,
     * or it already exists, or there is a clash.
     * @param byName object found by name
     * @param byPort object found by port
     * @param name the desired name of the object
     * @return true if already found, false if ok to create, null if conflict
     */
    <T> Boolean needToCreate(T byName, T byPort, String name) {
        if (byName != null && byPort != null) {
            // If objects with this name and/or port already exist...
            if (byName == byPort) {
                // ...and it is the same object, then do nothing
                return false;
            } else {
                // ...but if they are inconsistent, then fail fast
                return null;
            }
        } else if (byPort != null) {
            // If no object with the requested name, but the port number is found...
            if (name == null) {
                // ...and no name is requested, then do nothing, because the object already exists
                return false;
            } else {
                // ...but if a clashing name is requested, then fail fast
                return null;
            }
        } else {
            // Ok to create the object
            return true;
        }
    }

    String futureResourceId() {
        return new StringBuilder()
                .append(super.resourceIdBase())
                .append("/providers/Microsoft.Network/applicationGateways/")
                .append(this.name()).toString();
    }

     // Withers (fluent)

    @Override
    public ApplicationGatewayImpl withDisabledSslProtocol(ApplicationGatewaySslProtocol protocol) {
        if (protocol != null) {
            ApplicationGatewaySslPolicy policy = ensureSslPolicy();
            if (!policy.disabledSslProtocols().contains(protocol)) {
                policy.disabledSslProtocols().add(protocol);
            }
        }
        return this;
    }

    @Override
    public ApplicationGatewayImpl withDisabledSslProtocols(ApplicationGatewaySslProtocol... protocols) {
        if (protocols != null) {
            for (ApplicationGatewaySslProtocol protocol : protocols) {
                withDisabledSslProtocol(protocol);
            }
        }

        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutDisabledSslProtocol(ApplicationGatewaySslProtocol protocol) {
        if (this.inner().sslPolicy() != null && this.inner().sslPolicy().disabledSslProtocols() != null) {
            this.inner().sslPolicy().disabledSslProtocols().remove(protocol);
            if (this.inner().sslPolicy().disabledSslProtocols().isEmpty()) {
                this.withoutAnyDisabledSslProtocols();
            }
        }
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutDisabledSslProtocols(ApplicationGatewaySslProtocol...protocols) {
        if (protocols != null) {
            for (ApplicationGatewaySslProtocol protocol : protocols) {
                this.withoutDisabledSslProtocol(protocol);
            }
        }
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutAnyDisabledSslProtocols() {
        this.inner().withSslPolicy(null);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withInstanceCount(int capacity) {
        if (this.inner().sku() == null) {
            this.withSize(ApplicationGatewaySkuName.STANDARD_SMALL);
        }

        this.inner().sku().withCapacity(capacity);
        this.inner().withAutoscaleConfiguration(null);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withWebApplicationFirewall(boolean enabled, ApplicationGatewayFirewallMode mode) {
        this.inner().withWebApplicationFirewallConfiguration(
                new ApplicationGatewayWebApplicationFirewallConfiguration()
                        .withEnabled(enabled)
                        .withFirewallMode(mode)
                        .withRuleSetType("OWASP")
                        .withRuleSetVersion("3.0"));
        return this;
    }

    @Override
    public ApplicationGatewayImpl withWebApplicationFirewall(ApplicationGatewayWebApplicationFirewallConfiguration config) {
        this.inner().withWebApplicationFirewallConfiguration(config);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withAutoScale(int minCapacity, int maxCapacity) {
        this.inner().sku().withCapacity(null);
        this.inner().withAutoscaleConfiguration(
                new ApplicationGatewayAutoscaleConfiguration()
                        .withMinCapacity(minCapacity)
                        .withMaxCapacity(maxCapacity));
        return this;
    }

    @Override
    public ApplicationGatewayImpl withPrivateIPAddressDynamic() {
        ensureDefaultPrivateFrontend().withPrivateIPAddressDynamic();
        return this;
    }

    @Override
    public ApplicationGatewayImpl withPrivateIPAddressStatic(String ipAddress) {
        ensureDefaultPrivateFrontend().withPrivateIPAddressStatic(ipAddress);
        return this;
    }

    ApplicationGatewayImpl withFrontend(ApplicationGatewayFrontendImpl frontend) {
        if (frontend != null) {
            this.frontends.put(frontend.name(), frontend);
        }
        return this;
    }

    ApplicationGatewayImpl withProbe(ApplicationGatewayProbeImpl probe) {
        if (probe != null) {
            this.probes.put(probe.name(), probe);
        }
        return this;
    }

    ApplicationGatewayImpl withBackend(ApplicationGatewayBackendImpl backend) {
        if (backend != null) {
            this.backends.put(backend.name(), backend);
        }
        return this;
    }

    ApplicationGatewayImpl withAuthenticationCertificate(ApplicationGatewayAuthenticationCertificateImpl authCert) {
        if (authCert != null) {
            this.authCertificates.put(authCert.name(), authCert);
        }
        return this;
    }

    ApplicationGatewayImpl withSslCertificate(ApplicationGatewaySslCertificateImpl cert) {
        if (cert != null) {
            this.sslCerts.put(cert.name(), cert);
        }
        return this;
    }

    ApplicationGatewayImpl withHttpListener(ApplicationGatewayListenerImpl httpListener) {
        if (httpListener != null) {
            this.listeners.put(httpListener.name(), httpListener);
        }
        return this;
    }

    ApplicationGatewayImpl withRedirectConfiguration(ApplicationGatewayRedirectConfigurationImpl redirectConfig) {
        if (redirectConfig != null) {
            this.redirectConfigs.put(redirectConfig.name(), redirectConfig);
        }
        return this;
    }

    ApplicationGatewayImpl withUrlPathMap(ApplicationGatewayUrlPathMapImpl urlPathMap) {
        if (urlPathMap != null) {
            this.urlPathMaps.put(urlPathMap.name(), urlPathMap);
        }
        return this;
    }

    ApplicationGatewayImpl withRequestRoutingRule(ApplicationGatewayRequestRoutingRuleImpl rule) {
        if (rule != null) {
            this.rules.put(rule.name(), rule);
        }
        return this;
    }

    ApplicationGatewayImpl withBackendHttpConfiguration(ApplicationGatewayBackendHttpConfigurationImpl httpConfig) {
        if (httpConfig != null) {
            this.backendConfigs.put(httpConfig.name(), httpConfig);
        }
        return this;
    }

    @Override
    public ApplicationGatewayImpl withTier(ApplicationGatewayTier skuTier) {
        if (this.inner().sku() == null) {
            this.inner().withSku(new ApplicationGatewaySku().withCapacity(1));
        }
        this.inner().sku().withTier(skuTier);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withSize(ApplicationGatewaySkuName skuName) {
        if (this.inner().sku() == null) {
            // Create with default instance count
            this.inner().withSku(new ApplicationGatewaySku().withCapacity(1));
        }
        this.inner().sku().withName(skuName);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withExistingSubnet(Subnet subnet) {
        ensureDefaultIPConfig().withExistingSubnet(subnet);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withExistingSubnet(Network network, String subnetName) {
        ensureDefaultIPConfig().withExistingSubnet(network, subnetName);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withExistingSubnet(String networkResourceId, String subnetName) {
        ensureDefaultIPConfig().withExistingSubnet(networkResourceId, subnetName);
        return this;
    }

    ApplicationGatewayImpl withConfig(ApplicationGatewayIPConfigurationImpl config) {
        if (config != null) {
            this.ipConfigs.put(config.name(), config);
        }
        return this;
    }

    @Override
    public ApplicationGatewaySslCertificateImpl defineSslCertificate(String name) {
        return defineChild(name, this.sslCerts, ApplicationGatewaySslCertificateInner.class, ApplicationGatewaySslCertificateImpl.class);
    }

    //TODO @Override - since app gateways don't support more than one today, no need to expose this
    private ApplicationGatewayIPConfigurationImpl defineIPConfiguration(String name) {
        return defineChild(name, this.ipConfigs, ApplicationGatewayIPConfigurationInner.class, ApplicationGatewayIPConfigurationImpl.class);
    }

    //TODO @Override - since app gateways don't support more than one today, no need to expose this
    private ApplicationGatewayFrontendImpl defineFrontend(String name) {
        return defineChild(name, this.frontends, ApplicationGatewayFrontendIPConfiguration.class, ApplicationGatewayFrontendImpl.class);
        }

    @Override
    public ApplicationGatewayRedirectConfigurationImpl defineRedirectConfiguration(String name) {
        return defineChild(name, this.redirectConfigs, ApplicationGatewayRedirectConfigurationInner.class, ApplicationGatewayRedirectConfigurationImpl.class);
    }

    @Override
    public ApplicationGatewayRequestRoutingRuleImpl defineRequestRoutingRule(String name) {
        return defineChild(name, this.rules, ApplicationGatewayRequestRoutingRuleInner.class, ApplicationGatewayRequestRoutingRuleImpl.class);
    }

    @Override
    public ApplicationGatewayBackendImpl defineBackend(String name) {
        return defineChild(name, this.backends, ApplicationGatewayBackendAddressPool.class, ApplicationGatewayBackendImpl.class);
    }

    @Override
    public ApplicationGatewayAuthenticationCertificateImpl defineAuthenticationCertificate(String name) {
        return defineChild(name, this.authCertificates, ApplicationGatewayAuthenticationCertificateInner.class, ApplicationGatewayAuthenticationCertificateImpl.class);
    }

    @Override
    public ApplicationGatewayProbeImpl defineProbe(String name) {
        return defineChild(name, this.probes, ApplicationGatewayProbeInner.class, ApplicationGatewayProbeImpl.class);
    }

    @Override
    public ApplicationGatewayUrlPathMapImpl definePathBasedRoutingRule(String name) {
        ApplicationGatewayUrlPathMapImpl urlPathMap = defineChild(name, this.urlPathMaps, ApplicationGatewayUrlPathMapInner.class, ApplicationGatewayUrlPathMapImpl.class);
        SubResource ref = new SubResource().withId(futureResourceId() + "/urlPathMaps/" + name);
        // create corresponding request routing rule
        ApplicationGatewayRequestRoutingRuleInner inner = new ApplicationGatewayRequestRoutingRuleInner()
                .withName(name)
                .withRuleType(ApplicationGatewayRequestRoutingRuleType.PATH_BASED_ROUTING)
                .withUrlPathMap(ref);
        rules.put(name, new ApplicationGatewayRequestRoutingRuleImpl(inner, this));
        return urlPathMap;
    }

    @Override
    public ApplicationGatewayListenerImpl defineListener(String name) {
        return defineChild(name, this.listeners, ApplicationGatewayHttpListener.class, ApplicationGatewayListenerImpl.class);
    }

    @Override
    public ApplicationGatewayBackendHttpConfigurationImpl defineBackendHttpConfiguration(String name) {
        ApplicationGatewayBackendHttpConfigurationImpl config = defineChild(name, this.backendConfigs, ApplicationGatewayBackendHttpSettings.class, ApplicationGatewayBackendHttpConfigurationImpl.class);
        if (config.inner().id() == null) {
            return config.withPort(80); // Default port
        } else {
            return config;
        }
    }

    @SuppressWarnings("unchecked")
    private <ChildImplT, ChildT, ChildInnerT> ChildImplT defineChild(
            String name,
            Map<String, ChildT> children,
            Class<ChildInnerT> innerClass,
            Class<ChildImplT> implClass) {
        ChildT child = children.get(name);
        if (child == null) {
            ChildInnerT inner;
            try {
                inner = innerClass.newInstance();
                innerClass.getDeclaredMethod("withName", String.class).invoke(inner, name);
                return implClass.getDeclaredConstructor(innerClass, ApplicationGatewayImpl.class).newInstance(inner, this);
            } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e1) {
                return null;
            }
        } else {
            return (ChildImplT) child;
        }
    }

    @Override
    public ApplicationGatewayImpl withoutPrivateFrontend() {
        // Delete all private frontends
        List<String> toDelete = new ArrayList<>();
        for (ApplicationGatewayFrontend frontend : this.frontends.values()) {
            if (frontend.isPrivate()) {
                toDelete.add(frontend.name());
            }
        }

        for (String frontendName : toDelete) {
            this.frontends.remove(frontendName);
        }

        this.defaultPrivateFrontend = null;
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutPublicFrontend() {
        // Delete all public frontends
        List<String> toDelete = new ArrayList<>();
        for (ApplicationGatewayFrontend frontend : this.frontends.values()) {
            if (frontend.isPublic()) {
                toDelete.add(frontend.name());
            }
        }

        for (String frontendName : toDelete) {
            this.frontends.remove(frontendName);
        }

        this.defaultPublicFrontend = null;
        return this;
    }

    @Override
    public ApplicationGatewayImpl withFrontendPort(int portNumber) {
        return withFrontendPort(portNumber, null);
    }

    @Override
    public ApplicationGatewayImpl withFrontendPort(int portNumber, String name) {
        // Ensure inner ports list initialized
        List<ApplicationGatewayFrontendPort> frontendPorts = this.inner().frontendPorts();
        if (frontendPorts == null) {
            frontendPorts = new ArrayList<ApplicationGatewayFrontendPort>();
            this.inner().withFrontendPorts(frontendPorts);
        }

        // Attempt to find inner port by name if provided, or port number otherwise
        ApplicationGatewayFrontendPort frontendPortByName = null;
        ApplicationGatewayFrontendPort frontendPortByNumber = null;
        for (ApplicationGatewayFrontendPort inner : this.inner().frontendPorts()) {
            if (name != null && name.equalsIgnoreCase(inner.name())) {
                frontendPortByName = inner;
            }
            if (inner.port() == portNumber) {
                frontendPortByNumber = inner;
            }
        }

        Boolean needToCreate = this.needToCreate(frontendPortByName, frontendPortByNumber, name);
        if (Boolean.TRUE.equals(needToCreate)) {
            // If no conflict, create a new port
            if (name == null) {
                // No name specified, so auto-name it
                name = SdkContext.randomResourceName("port", 9);
            }

            frontendPortByName = new ApplicationGatewayFrontendPort()
                    .withName(name)
                    .withPort(portNumber);
            frontendPorts.add(frontendPortByName);
            return this;
        } else if (Boolean.FALSE.equals(needToCreate)) {
            // If found matching port, then nothing needs to happen
            return this;
        } else {
            // If name conflict for the same port number, then fail
            return null;
        }
    }

    @Override
    public ApplicationGatewayImpl withPrivateFrontend() {
        /* NOTE: This logic is a workaround for the unusual Azure API logic:
         * - although app gateway API definition allows multiple IP configs, only one is allowed by the service currently;
         * - although app gateway frontend API definition allows for multiple frontends, only one is allowed by the service today;
         * - and although app gateway API definition allows different subnets to be specified between the IP configs and frontends, the service
         * requires the frontend and the containing subnet to be one and the same currently.
         *
         * So the logic here attempts to figure out from the API what that containing subnet for the app gateway is so that the user wouldn't
         * have to re-enter it redundantly when enabling a private frontend, since only that one subnet is supported anyway.
         *
         * TODO: When the underlying Azure API is reworked to make more sense, or the app gateway service starts supporting the functionality
         * that the underlying API implies is supported, this model and implementation should be revisited.
         */
        ensureDefaultPrivateFrontend();
        return this;
    }

    // Withers

    @Override
    public ApplicationGatewayImpl withExistingPublicIPAddress(PublicIPAddress publicIPAddress) {
        ensureDefaultPublicFrontend().withExistingPublicIPAddress(publicIPAddress);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withExistingPublicIPAddress(String resourceId) {
        ensureDefaultPublicFrontend().withExistingPublicIPAddress(resourceId);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withNewPublicIPAddress(Creatable<PublicIPAddress> creatable) {
        final String name = ensureDefaultPublicFrontend().name();
        this.creatablePipsByFrontend.put(name, this.addDependency(creatable));
        return this;
    }

    @Override
    public ApplicationGatewayImpl withNewPublicIPAddress() {
        ensureDefaultPublicFrontend();
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutBackendFqdn(String fqdn) {
        for (ApplicationGatewayBackend backend : this.backends.values()) {
            ((ApplicationGatewayBackendImpl) backend).withoutFqdn(fqdn);
        }
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutBackendIPAddress(String ipAddress) {
        for (ApplicationGatewayBackend backend : this.backends.values()) {
            ApplicationGatewayBackendImpl backendImpl = (ApplicationGatewayBackendImpl) backend;
            backendImpl.withoutIPAddress(ipAddress);
        }
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutIPConfiguration(String ipConfigurationName) {
        this.ipConfigs.remove(ipConfigurationName);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutFrontend(String frontendName) {
        this.frontends.remove(frontendName);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutFrontendPort(String name) {
        if (this.inner().frontendPorts() == null) {
            return this;
        }

        for (int i = 0; i < this.inner().frontendPorts().size(); i++) {
            ApplicationGatewayFrontendPort inner = this.inner().frontendPorts().get(i);
            if (inner.name().equalsIgnoreCase(name)) {
                this.inner().frontendPorts().remove(i);
                break;
            }
        }

        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutFrontendPort(int portNumber) {
        for (int i = 0; i < this.inner().frontendPorts().size(); i++) {
            ApplicationGatewayFrontendPort inner = this.inner().frontendPorts().get(i);
            if (inner.port().equals(portNumber)) {
                this.inner().frontendPorts().remove(i);
                break;
            }
        }

        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutCertificate(String name) {
        return this.withoutSslCertificate(name);
    }

    @Override
    public ApplicationGatewayImpl withoutSslCertificate(String name) {
        this.sslCerts.remove(name);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutAuthenticationCertificate(String name) {
        this.authCertificates.remove(name);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutProbe(String name) {
        this.probes.remove(name);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutListener(String name) {
        this.listeners.remove(name);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutRedirectConfiguration(String name) {
        this.redirectConfigs.remove(name);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutUrlPathMap(String name) {
        // Remove associated request routing rule
        for (ApplicationGatewayRequestRoutingRule rule : rules.values()) {
            if (rule.urlPathMap() != null && name.equals(rule.urlPathMap().name())) {
                rules.remove(rule.name());
                break;
            }
        }
        this.urlPathMaps.remove(name);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutRequestRoutingRule(String name) {
        this.rules.remove(name);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutBackend(String backendName) {
        this.backends.remove(backendName);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withHttp2() {
        inner().withEnableHttp2(true);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withoutHttp2() {
        inner().withEnableHttp2(false);
        return this;
    }

    @Override
    public ApplicationGatewayImpl withAvailabilityZone(AvailabilityZoneId zoneId) {
        if (this.inner().zones() == null) {
            this.inner().withZones(new ArrayList<String>());
        }
        if (!this.inner().zones().contains(zoneId.toString())) {
            this.inner().zones().add(zoneId.toString());
        }
        return this;
    }

    @Override
    public ApplicationGatewayBackendImpl updateBackend(String name) {
        return (ApplicationGatewayBackendImpl) this.backends.get(name);
    }

    @Override
    public ApplicationGatewayFrontendImpl updatePublicFrontend() {
        return (ApplicationGatewayFrontendImpl) defaultPublicFrontend();
    }

    @Override
    public ApplicationGatewayListenerImpl updateListener(String name) {
        return (ApplicationGatewayListenerImpl) this.listeners.get(name);
    }

    @Override
    public ApplicationGatewayRedirectConfigurationImpl updateRedirectConfiguration(String name) {
        return (ApplicationGatewayRedirectConfigurationImpl) this.redirectConfigs.get(name);
    }

    @Override
    public ApplicationGatewayRequestRoutingRuleImpl updateRequestRoutingRule(String name) {
        return (ApplicationGatewayRequestRoutingRuleImpl) this.rules.get(name);
    }

    @Override
    public ApplicationGatewayImpl withoutBackendHttpConfiguration(String name) {
        this.backendConfigs.remove(name);
        return this;
    }

    @Override
    public ApplicationGatewayBackendHttpConfigurationImpl updateBackendHttpConfiguration(String name) {
        return (ApplicationGatewayBackendHttpConfigurationImpl) this.backendConfigs.get(name);
    }

    @Override
    public ApplicationGatewayIPConfigurationImpl updateIPConfiguration(String ipConfigurationName) {
        return (ApplicationGatewayIPConfigurationImpl) this.ipConfigs.get(ipConfigurationName);
    }

    @Override
    public ApplicationGatewayProbeImpl updateProbe(String name) {
        return (ApplicationGatewayProbeImpl) this.probes.get(name);
    }

    @Override
    public ApplicationGatewayIPConfigurationImpl updateDefaultIPConfiguration() {
        return (ApplicationGatewayIPConfigurationImpl) this.defaultIPConfiguration();
    }

    @Override
    public ApplicationGatewayIPConfigurationImpl defineDefaultIPConfiguration() {
        return ensureDefaultIPConfig();
    }

    @Override
    public ApplicationGatewayFrontendImpl definePublicFrontend() {
        return ensureDefaultPublicFrontend();
    }

    @Override
    public ApplicationGatewayFrontendImpl definePrivateFrontend() {
        return ensureDefaultPrivateFrontend();
    }

    @Override
    public ApplicationGatewayFrontendImpl updateFrontend(String frontendName) {
        return (ApplicationGatewayFrontendImpl) this.frontends.get(frontendName);
    }

    @Override
    public ApplicationGatewayUrlPathMapImpl updateUrlPathMap(String name) {
        return (ApplicationGatewayUrlPathMapImpl) this.urlPathMaps.get(name);
    }

    // Getters

    @Override
    public Collection<ApplicationGatewaySslProtocol> disabledSslProtocols() {
        if (this.inner().sslPolicy() == null || this.inner().sslPolicy().disabledSslProtocols() == null) {
            return new ArrayList<>();
        } else {
            return Collections.unmodifiableCollection(this.inner().sslPolicy().disabledSslProtocols());
        }
    }

    @Override
    public ApplicationGatewayFrontend defaultPrivateFrontend() {
        // Default means the only private one or the one tracked as default, if more than one private present
        Map<String, ApplicationGatewayFrontend> privateFrontends = this.privateFrontends();
        if (privateFrontends.size() == 1) {
            this.defaultPrivateFrontend = (ApplicationGatewayFrontendImpl) privateFrontends.values().iterator().next();
        } else if (this.frontends().size() == 0) {
            this.defaultPrivateFrontend = null;
        }

        return this.defaultPrivateFrontend;
    }

    @Override
    public ApplicationGatewayFrontend defaultPublicFrontend() {
        // Default means the only public one or the one tracked as default, if more than one public present
        Map<String, ApplicationGatewayFrontend> publicFrontends = this.publicFrontends();
        if (publicFrontends.size() == 1) {
            this.defaultPublicFrontend = (ApplicationGatewayFrontendImpl) publicFrontends.values().iterator().next();
        } else if (this.frontends().size() == 0) {
            this.defaultPublicFrontend = null;
        }

        return this.defaultPublicFrontend;
    }

    @Override
    public ApplicationGatewayIPConfiguration defaultIPConfiguration() {
        // Default means the only one
        if (this.ipConfigs.size() == 1) {
            return this.ipConfigs.values().iterator().next();
        } else {
            return null;
        }
    }

    @Override
    public ApplicationGatewayListener listenerByPortNumber(int portNumber) {
        ApplicationGatewayListener listener = null;
        for (ApplicationGatewayListener l : this.listeners.values()) {
            if (l.frontendPortNumber() == portNumber) {
                listener = l;
                break;
            }
        }
        return listener;
    }

    @Override
    public Map<String, ApplicationGatewayAuthenticationCertificate> authenticationCertificates() {
        return Collections.unmodifiableMap(this.authCertificates);
    }

    @Override
    public boolean isHttp2Enabled() {
        return Utils.toPrimitiveBoolean(inner().enableHttp2());
    }

    @Override
    public Map<String, ApplicationGatewayUrlPathMap> urlPathMaps() {
        return Collections.unmodifiableMap(this.urlPathMaps);
    }

    @Override
    public Set<AvailabilityZoneId> availabilityZones() {
        Set<AvailabilityZoneId> zones = new TreeSet<>();
        if (this.inner().zones() != null) {
            for (String zone : this.inner().zones()) {
                zones.add(AvailabilityZoneId.fromString(zone));
            }
        }
        return Collections.unmodifiableSet(zones);
    }

    @Override
    public Map<String, ApplicationGatewayBackendHttpConfiguration> backendHttpConfigurations() {
        return Collections.unmodifiableMap(this.backendConfigs);
    }

    @Override
    public Map<String, ApplicationGatewayBackend> backends() {
        return Collections.unmodifiableMap(this.backends);
    }

    @Override
    public Map<String, ApplicationGatewayRequestRoutingRule> requestRoutingRules() {
        return Collections.unmodifiableMap(this.rules);
    }

    @Override
    public Map<String, ApplicationGatewayFrontend> frontends() {
        return Collections.unmodifiableMap(this.frontends);
    }

    @Override
    public Map<String, ApplicationGatewayProbe> probes() {
        return Collections.unmodifiableMap(this.probes);
    }

    @Override
    public Map<String, ApplicationGatewaySslCertificate> sslCertificates() {
        return Collections.unmodifiableMap(this.sslCerts);
    }

    @Override
    public Map<String, ApplicationGatewayListener> listeners() {
        return Collections.unmodifiableMap(this.listeners);
    }

    @Override
    public Map<String, ApplicationGatewayRedirectConfiguration> redirectConfigurations() {
        return Collections.unmodifiableMap(this.redirectConfigs);
    }

    @Override
    public Map<String, ApplicationGatewayIPConfiguration> ipConfigurations() {
        return Collections.unmodifiableMap(this.ipConfigs);
    }

    @Override
    public ApplicationGatewaySku sku() {
        return this.inner().sku();
    }

    @Override
    public ApplicationGatewayOperationalState operationalState() {
        return this.inner().operationalState();
    }

    @Override
    public Map<String, Integer> frontendPorts() {
        Map<String, Integer> ports = new TreeMap<>();
        if (this.inner().frontendPorts() != null) {
            for (ApplicationGatewayFrontendPort portInner : this.inner().frontendPorts()) {
                ports.put(portInner.name(), portInner.port());
            }
        }
        return Collections.unmodifiableMap(ports);
    }

    @Override
    public String frontendPortNameFromNumber(int portNumber) {
        String portName = null;
        for (Entry<String, Integer> portEntry : this.frontendPorts().entrySet()) {
            if (portNumber == portEntry.getValue()) {
                portName = portEntry.getKey();
                break;
            }
        }
        return portName;
    }

    private SubResource defaultSubnetRef() {
        ApplicationGatewayIPConfiguration ipConfig = defaultIPConfiguration();
        if (ipConfig == null) {
            return null;
        } else {
            return ipConfig.inner().subnet();
        }
    }

    @Override
    public String networkId() {
        SubResource subnetRef = defaultSubnetRef();
        if (subnetRef == null) {
            return null;
        } else {
            return ResourceUtils.parentResourceIdFromResourceId(subnetRef.id());
        }
    }

    @Override
    public String subnetName() {
        SubResource subnetRef = defaultSubnetRef();
        if (subnetRef == null) {
            return null;
        } else {
            return ResourceUtils.nameFromResourceId(subnetRef.id());
        }
    }

    @Override
    public String privateIPAddress() {
        ApplicationGatewayFrontend frontend = defaultPrivateFrontend();
        if (frontend == null) {
            return null;
        } else {
            return frontend.privateIPAddress();
        }
    }

    @Override
    public IPAllocationMethod privateIPAllocationMethod() {
        ApplicationGatewayFrontend frontend = defaultPrivateFrontend();
        if (frontend == null) {
            return null;
        } else {
            return frontend.privateIPAllocationMethod();
        }
    }

    @Override
    public boolean isPrivate() {
        for (ApplicationGatewayFrontend frontend : this.frontends.values()) {
            if (frontend.isPrivate()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPublic() {
        for (ApplicationGatewayFrontend frontend : this.frontends.values()) {
            if (frontend.isPublic()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<String, ApplicationGatewayFrontend> publicFrontends() {
        Map<String, ApplicationGatewayFrontend> publicFrontends = new TreeMap<>();
        for (ApplicationGatewayFrontend frontend : this.frontends().values()) {
            if (frontend.isPublic()) {
                publicFrontends.put(frontend.name(), frontend);
            }
        }

        return Collections.unmodifiableMap(publicFrontends);
    }

    @Override
    public Map<String, ApplicationGatewayFrontend> privateFrontends() {
        Map<String, ApplicationGatewayFrontend> privateFrontends = new TreeMap<>();
        for (ApplicationGatewayFrontend frontend : this.frontends.values()) {
            if (frontend.isPrivate()) {
                privateFrontends.put(frontend.name(), frontend);
            }
        }

        return Collections.unmodifiableMap(privateFrontends);
    }

    @Override
    public int instanceCount() {
        if (this.sku() != null && this.sku().capacity() != null) {
            return this.sku().capacity();
        } else {
            return 1;
        }
    }

    @Override
    public ApplicationGatewaySkuName size() {
        if (this.sku() != null && this.sku().name() != null) {
            return this.sku().name();
        } else {
            return ApplicationGatewaySkuName.STANDARD_SMALL;
        }
    }

    @Override
    public ApplicationGatewayTier tier() {
        if (this.sku() != null && this.sku().tier() != null) {
            return this.sku().tier();
        } else {
            return ApplicationGatewayTier.STANDARD;
        }
    }

    @Override
    public ApplicationGatewayAutoscaleConfiguration autoscaleConfiguration() {
        return this.inner().autoscaleConfiguration();
    }

    @Override
    public ApplicationGatewayWebApplicationFirewallConfiguration webApplicationFirewallConfiguration() {
        return this.inner().webApplicationFirewallConfiguration();
    }

    @Override
    public Update withoutPublicIPAddress() {
        return this.withoutPublicFrontend();
    }

    // Actions

    @Override
    public void start() {
        this.startAsync().await();
    }

    @Override
    public void stop() {
        this.stopAsync().await();
    }

    @Override
    public Completable startAsync() {
        Observable<Void> startObservable = this.manager().inner().applicationGateways().startAsync(this.resourceGroupName(), this.name());
        Observable<ApplicationGateway> refreshObservable = refreshAsync();

        // Refresh after start to ensure the app gateway operational state is updated
        return Observable.concat(startObservable, refreshObservable).toCompletable();
    }

    @Override
    public Completable stopAsync() {
        Observable<Void> stopObservable = this.manager().inner().applicationGateways().stopAsync(this.resourceGroupName(), this.name());
        Observable<ApplicationGateway> refreshObservable = refreshAsync();

        // Refresh after stop to ensure the app gateway operational state is updated
        return Observable.concat(stopObservable, refreshObservable).toCompletable();
    }

    private ApplicationGatewaySslPolicy ensureSslPolicy() {
        ApplicationGatewaySslPolicy policy = this.inner().sslPolicy();
        if (policy == null) {
            policy = new ApplicationGatewaySslPolicy();
            this.inner().withSslPolicy(policy);
        }

        List<ApplicationGatewaySslProtocol> protocols = policy.disabledSslProtocols();
        if (protocols == null) {
            protocols = new ArrayList<>();
            policy.withDisabledSslProtocols(protocols);
        }

        return policy;
    }

    @Override
    public Map<String, ApplicationGatewayBackendHealth> checkBackendHealth() {
        return this.checkBackendHealthAsync().toBlocking().last();
    }

    @Override
    public Observable<Map<String, ApplicationGatewayBackendHealth>> checkBackendHealthAsync() {
        return this.manager().inner().applicationGateways()
                .backendHealthAsync(this.resourceGroupName(), this.name())
                .map(new Func1<ApplicationGatewayBackendHealthInner, Map<String, ApplicationGatewayBackendHealth>>() {
                    @Override
                    public Map<String, ApplicationGatewayBackendHealth> call(ApplicationGatewayBackendHealthInner inner) {
                        Map<String, ApplicationGatewayBackendHealth> backendHealths = new TreeMap<>();
                        if (inner != null) {
                            for (ApplicationGatewayBackendHealthPool healthInner : inner.backendAddressPools()) {
                                ApplicationGatewayBackendHealth backendHealth = new ApplicationGatewayBackendHealthImpl(healthInner, ApplicationGatewayImpl.this);
                                backendHealths.put(backendHealth.name(), backendHealth);
                            }
                        }
                        return Collections.unmodifiableMap(backendHealths);
                    }
                });
    }
}
