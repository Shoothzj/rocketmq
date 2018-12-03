/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.acl.plain;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.acl.common.AclUtils;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;

public class PlainPermissionLoader {

    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.ACL_PLUG_LOGGER_NAME);

    private String fileHome = System.getProperty(MixAll.ROCKETMQ_HOME_PROPERTY,
        System.getenv(MixAll.ROCKETMQ_HOME_ENV));

    private Map<String/** account **/, List<AuthenticationInfo>> accessControlMap = new HashMap<>();

    private AuthenticationInfo authenticationInfo;

    private RemoteAddressStrategyFactory remoteAddressStrategyFactory = new RemoteAddressStrategyFactory();

    private AccessContralAnalysis accessContralAnalysis = new AccessContralAnalysis();

    private Class<?> accessContralAnalysisClass = RequestCode.class;

    private boolean isWatchStart;

    public PlainPermissionLoader() {
        initialize();
        watch();
    }

    public void initialize() {
        BrokerAccessControlTransport accessControlTransport = AclUtils.getYamlDataObject(fileHome + "/conf/transport.yml", BrokerAccessControlTransport.class);
        if (accessControlTransport == null) {
            throw new AclPlugRuntimeException("transport.yml file  is no data");
        }
        log.info("BorkerAccessControlTransport data is : ", accessControlTransport.toString());
        accessContralAnalysis.analysisClass(accessContralAnalysisClass);
        setBrokerAccessControlTransport(accessControlTransport);
    }

    private void watch() {
        String version = System.getProperty("java.version");
        log.info("java.version is : {}", version);
        String[] str = StringUtils.split(version, ".");
        if (Integer.valueOf(str[1]) < 7) {
            log.warn("wacth need jdk 1.7 support , current version no support");
            return;
        }
        try {
            final WatchService watcher = FileSystems.getDefault().newWatchService();
            Path p = Paths.get(fileHome + "/conf/");
            p.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            ServiceThread watcherServcie = new ServiceThread() {

                public void run() {
                    while (true) {
                        try {
                            while (true) {
                                WatchKey watchKey = watcher.take();
                                List<WatchEvent<?>> watchEvents = watchKey.pollEvents();
                                for (WatchEvent<?> event : watchEvents) {
                                    if ("transport.yml".equals(event.context().toString()) &&
                                        (StandardWatchEventKinds.ENTRY_MODIFY.equals(event.kind()) || StandardWatchEventKinds.ENTRY_CREATE.equals(event.kind()))) {
                                        log.info("transprot.yml make a difference  change is : ", event.toString());
                                        PlainPermissionLoader.this.cleanAuthenticationInfo();
                                        initialize();
                                    }
                                }
                                watchKey.reset();
                            }
                        } catch (InterruptedException e) {
                            log.error(e.getMessage(), e);
                        }
                    }
                }

                @Override
                public String getServiceName() {
                    return "watcherServcie";
                }

            };
            watcherServcie.start();
            log.info("succeed start watcherServcie");
            this.isWatchStart = true;
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    private void handleAccessControl(PlainAccessResource plainAccessResource) {
        if (plainAccessResource instanceof BrokerAccessControl) {
            BrokerAccessControl brokerAccessControl = (BrokerAccessControl) plainAccessResource;
            if (brokerAccessControl.isAdmin()) {
                brokerAccessControl.setUpdateAndCreateSubscriptiongroup(true);
                brokerAccessControl.setDeleteSubscriptiongroup(true);
                brokerAccessControl.setUpdateAndCreateTopic(true);
                brokerAccessControl.setDeleteTopicInbroker(true);
                brokerAccessControl.setUpdateBrokerConfig(true);
            }
        }
    }

    void cleanAuthenticationInfo() {
        accessControlMap.clear();
        authenticationInfo = null;
    }

    public void setAccessControl(PlainAccessResource plainAccessResource) throws AclPlugRuntimeException {
        if (plainAccessResource.getAccessKey() == null || plainAccessResource.getSignature() == null
            || plainAccessResource.getAccessKey().length() <= 6 || plainAccessResource.getSignature().length() <= 6) {
            throw new AclPlugRuntimeException(String.format(
                "The account password cannot be null and is longer than 6, account is %s  password is %s",
                plainAccessResource.getAccessKey(), plainAccessResource.getSignature()));
        }
        try {
            handleAccessControl(plainAccessResource);
            RemoteAddressStrategy remoteAddressStrategy = remoteAddressStrategyFactory.getNetaddressStrategy(plainAccessResource);
            List<AuthenticationInfo> accessControlAddressList = accessControlMap.get(plainAccessResource.getAccessKey());
            if (accessControlAddressList == null) {
                accessControlAddressList = new ArrayList<>();
                accessControlMap.put(plainAccessResource.getAccessKey(), accessControlAddressList);
            }
            AuthenticationInfo authenticationInfo = new AuthenticationInfo(
                accessContralAnalysis.analysis(plainAccessResource), plainAccessResource, remoteAddressStrategy);
            accessControlAddressList.add(authenticationInfo);
            log.info("authenticationInfo is {}", authenticationInfo.toString());
        } catch (Exception e) {
            throw new AclPlugRuntimeException(
                String.format("Exception info %s  %s", e.getMessage(), plainAccessResource.toString()), e);
        }
    }

    public void setAccessControlList(List<PlainAccessResource> plainAccessResourceList) throws AclPlugRuntimeException {
        for (PlainAccessResource plainAccessResource : plainAccessResourceList) {
            setAccessControl(plainAccessResource);
        }
    }

    public void setNetaddressAccessControl(PlainAccessResource plainAccessResource) throws AclPlugRuntimeException {
        try {
            authenticationInfo = new AuthenticationInfo(accessContralAnalysis.analysis(plainAccessResource), plainAccessResource, remoteAddressStrategyFactory.getNetaddressStrategy(plainAccessResource));
            log.info("default authenticationInfo is {}", authenticationInfo.toString());
        } catch (Exception e) {
            throw new AclPlugRuntimeException(plainAccessResource.toString(), e);
        }

    }

    public AuthenticationInfo getAccessControl(PlainAccessResource plainAccessResource) {
        if (plainAccessResource.getAccessKey() == null && authenticationInfo != null) {
            return authenticationInfo.getRemoteAddressStrategy().match(plainAccessResource) ? authenticationInfo : null;
        } else {
            List<AuthenticationInfo> accessControlAddressList = accessControlMap.get(plainAccessResource.getAccessKey());
            if (accessControlAddressList != null) {
                for (AuthenticationInfo ai : accessControlAddressList) {
                    if (ai.getRemoteAddressStrategy().match(plainAccessResource) && ai.getPlainAccessResource().getSignature().equals(plainAccessResource.getSignature())) {
                        return ai;
                    }
                }
            }
        }
        return null;
    }

    public AuthenticationResult eachCheckAuthentication(PlainAccessResource plainAccessResource) {
        AuthenticationResult authenticationResult = new AuthenticationResult();
        AuthenticationInfo authenticationInfo = getAccessControl(plainAccessResource);
        if (authenticationInfo != null) {
            boolean boo = authentication(authenticationInfo, plainAccessResource, authenticationResult);
            authenticationResult.setSucceed(boo);
            authenticationResult.setPlainAccessResource(authenticationInfo.getPlainAccessResource());
        } else {
            authenticationResult.setResultString("plainAccessResource is null, Please check login, password, IP\"");
        }
        return authenticationResult;
    }

    void setBrokerAccessControlTransport(BrokerAccessControlTransport transport) {
        if (transport.getOnlyNetAddress() == null && (transport.getList() == null || transport.getList().size() == 0)) {
            throw new AclPlugRuntimeException("onlyNetAddress and list  can't be all empty");
        }

        if (transport.getOnlyNetAddress() != null) {
            this.setNetaddressAccessControl(transport.getOnlyNetAddress());
        }
        if (transport.getList() != null || transport.getList().size() > 0) {
            for (BrokerAccessControl accessControl : transport.getList()) {
                this.setAccessControl(accessControl);
            }
        }
    }

    public boolean authentication(AuthenticationInfo authenticationInfo, PlainAccessResource plainAccessResource,
        AuthenticationResult authenticationResult) {
        int code = plainAccessResource.getRequestCode();
        if (!authenticationInfo.getAuthority().get(code)) {
            authenticationResult.setResultString(String.format("code is %d Authentication failed", code));
            return false;
        }
        if (!(authenticationInfo.getPlainAccessResource() instanceof BrokerAccessControl)) {
            return true;
        }
        BrokerAccessControl borker = (BrokerAccessControl) authenticationInfo.getPlainAccessResource();
        String topicName = plainAccessResource.getTopic();
        if (code == 10 || code == 310 || code == 320) {
            if (borker.getPermitSendTopic().contains(topicName)) {
                return true;
            }
            if (borker.getNoPermitSendTopic().contains(topicName)) {
                authenticationResult.setResultString(String.format("noPermitSendTopic include %s", topicName));
                return false;
            }
            return borker.getPermitSendTopic().isEmpty() ? true : false;
        } else if (code == 11) {
            if (borker.getPermitPullTopic().contains(topicName)) {
                return true;
            }
            if (borker.getNoPermitPullTopic().contains(topicName)) {
                authenticationResult.setResultString(String.format("noPermitPullTopic include %s", topicName));
                return false;
            }
            return borker.getPermitPullTopic().isEmpty() ? true : false;
        }
        return true;
    }

    public boolean isWatchStart() {
        return isWatchStart;
    }

    public static class AccessContralAnalysis {

        private Map<Class<?>, Map<Integer, Field>> classTocodeAndMentod = new HashMap<>();

        private Map<String, Integer> fieldNameAndCode = new HashMap<>();

        public void analysisClass(Class<?> clazz) {
            Field[] fields = clazz.getDeclaredFields();
            try {
                for (Field field : fields) {
                    if (field.getType().equals(int.class)) {
                        String name = StringUtils.replace(field.getName(), "_", "").toLowerCase();
                        fieldNameAndCode.put(name, (Integer) field.get(null));
                    }
                }
            } catch (IllegalArgumentException | IllegalAccessException e) {
                throw new AclPlugRuntimeException(String.format("analysis on failure Class is %s", clazz.getName()), e);
            }
        }

        public Map<Integer, Boolean> analysis(PlainAccessResource plainAccessResource) {
            Class<? extends PlainAccessResource> clazz = plainAccessResource.getClass();
            Map<Integer, Field> codeAndField = classTocodeAndMentod.get(clazz);
            if (codeAndField == null) {
                codeAndField = new HashMap<>();
                Field[] fields = clazz.getDeclaredFields();
                for (Field field : fields) {
                    if ("admin".equals(field.getName()))
                        continue;
                    if (!field.getType().equals(boolean.class))
                        continue;
                    Integer code = fieldNameAndCode.get(field.getName().toLowerCase());
                    if (code == null) {
                        throw new AclPlugRuntimeException(
                            String.format("field nonexistent in code  fieldName is %s", field.getName()));
                    }
                    field.setAccessible(true);
                    codeAndField.put(code, field);

                }
                if (codeAndField.isEmpty()) {
                    throw new AclPlugRuntimeException(String.format("PlainAccessResource nonexistent code , name %s",
                        plainAccessResource.getClass().getName()));
                }
                classTocodeAndMentod.put(clazz, codeAndField);
            }
            Iterator<Entry<Integer, Field>> it = codeAndField.entrySet().iterator();
            Map<Integer, Boolean> authority = new HashMap<>();
            try {
                while (it.hasNext()) {
                    Entry<Integer, Field> e = it.next();
                    authority.put(e.getKey(), (Boolean) e.getValue().get(plainAccessResource));
                }
            } catch (IllegalArgumentException | IllegalAccessException e) {
                throw new AclPlugRuntimeException(
                    String.format("analysis on failure PlainAccessResource is %s", PlainAccessResource.class.getName()), e);
            }
            return authority;
        }

    }

    public static class BrokerAccessControlTransport {

        private BrokerAccessControl onlyNetAddress;

        private List<BrokerAccessControl> list;

        public BrokerAccessControl getOnlyNetAddress() {
            return onlyNetAddress;
        }

        public void setOnlyNetAddress(BrokerAccessControl onlyNetAddress) {
            this.onlyNetAddress = onlyNetAddress;
        }

        public List<BrokerAccessControl> getList() {
            return list;
        }

        public void setList(List<BrokerAccessControl> list) {
            this.list = list;
        }

        @Override
        public String toString() {
            return "BorkerAccessControlTransport [onlyNetAddress=" + onlyNetAddress + ", list=" + list + "]";
        }
    }
}