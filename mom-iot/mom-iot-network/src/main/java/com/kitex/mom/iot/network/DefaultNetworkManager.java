package com.kitex.mom.iot.network;


import com.kitex.mom.iot.core.network.NetworkConfigManager;
import com.kitex.mom.iot.core.network.NetworkProperties;
import com.kitex.mom.iot.core.network.NetworkType;
import com.kitex.mom.iot.core.utils.Function3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 默认网络管理器实现
 */
public class DefaultNetworkManager implements NetworkManager {

    private final Map<String, NetworkProvider<Object>> providers = new ConcurrentHashMap<>();

    private final Map<String, Map<String, Network>> networks = new ConcurrentHashMap<>();

    private final NetworkConfigManager configManager;

    public DefaultNetworkManager(NetworkConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 注册网络组件提供商
     *
     * @param provider 网络组件提供商
     * @return 网络管理器
     */
    public NetworkManager register(NetworkProvider<Object> provider) {
        providers.put(provider.getType().getId(), provider);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Network> T getNetwork(NetworkType type, String id) {
        return getNetwork(type.getId(), id);
    }


    private <T extends Network> T getNetwork(String type, String id) {
        Map<String, Network> stringNetworkMap = networks.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
        // 如果存在对应的网络组件, 直接返回 否则 创建一个网络组件
        return (T) stringNetworkMap.computeIfAbsent(id, (key) -> handleConfig(NetworkType.of(type), key, this::doCreate));
    }

    private Network doCreate(NetworkProvider<Object> provider, String s, Object properties) {
        return provider.createNetwork(properties);
    }


    private Network createOrUpdate(NetworkProvider<Object> provider, String id, Object properties) {
        Map<String, Network> stringNetworkMap = networks.computeIfAbsent(provider.getType().getId(), k -> new ConcurrentHashMap<>());
        return stringNetworkMap.compute(id, (key, network) -> {
            if (network == null) {
                return provider.createNetwork(properties);
            } else {
                return provider.reload(network, properties);
            }
        });
    }

    private Network handleConfig(NetworkType type, String id, Function3<NetworkProvider<Object>, String, Object, Network> handler) {
        // 获取网络组件提供商
        NetworkProvider<Object> networkProvider = (NetworkProvider<Object>) this.getProvider(type.getId()).orElseThrow(() -> new RuntimeException("未找到网络组件提供商: " + type.getId()));


        NetworkProperties config = configManager.getConfig(type, id);
        if (config.isEnabled()) {
            Object config1 = networkProvider.createConfig(config);
            return handler.apply(networkProvider, id, config1);
        }
        return null;
    }


    @Override
    public List<Network> getNetworks() {
        return networks.values().stream().flatMap(map -> map.values().stream()).collect(Collectors.toList());
    }


    @Override
    public List<NetworkProvider<?>> getProviders() {
        return new ArrayList<>(providers.values());
    }

    @Override
    public Optional<NetworkProvider<?>> getProvider(String type) {
        return Optional.ofNullable(providers.get(type));
    }

    @Override
    public void reload(NetworkType type, String id) {
        doReload(type.getId(), id);
    }

    private void doReload(String type, String id) {
        handleConfig(NetworkType.of(type), id, this::createOrUpdate);
    }

    @Override
    public void shutdown(NetworkType type, String id) {
        Map<String, Network> stringNetworkMap = networks.computeIfAbsent(type.getId(), k -> new ConcurrentHashMap<>());
        if (stringNetworkMap.isEmpty()) {
            return;
        }
        Network remove = stringNetworkMap.remove(id);
        if (remove != null) {
            remove.shutdown();
        }
    }

    @Override
    public void destroy(NetworkType type, String id) {
        shutdown(type, id);
    }


//    @Override
//    public void run(String... args) throws Exception {
//
//        // 定时检查网络组件状态
//        this.checkNetwork();
//    }

    protected void checkNetwork() {
        List<Network> networks = getNetworks();
        networks.stream().filter(i -> !i.isAlive()).forEach(network -> {
            NetworkProvider<Object> objectNetworkProvider = providers.get(network.getType().getId());
            if (objectNetworkProvider == null || !objectNetworkProvider.isReusable()) {
                return;
            }
            NetworkProperties config = configManager.getConfig(network.getType(), network.getId());
            Object config1 = objectNetworkProvider.createConfig(config);
            this.createOrUpdate(objectNetworkProvider, network.getId(), config1);
        });

    }
}