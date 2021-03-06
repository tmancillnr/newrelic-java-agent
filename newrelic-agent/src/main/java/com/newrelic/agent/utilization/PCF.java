/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.newrelic.agent.utilization;

import com.newrelic.agent.Agent;
import com.newrelic.agent.MetricNames;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class PCF implements CloudVendor {
    static String PROVIDER = "pcf";
    private final CloudUtility cloudUtility;

    public PCF(CloudUtility cloudUtility) {
        this.cloudUtility = cloudUtility;
    }

    // PCF environment variables
    private static final String CF_INSTANCE_GUID_ENV = "CF_INSTANCE_GUID";
    private static final String CF_INSTANCE_IP_ENV = "CF_INSTANCE_IP";
    private static final String MEMORY_LIMIT_ENV = "MEMORY_LIMIT";

    // PCF map keys. These are the keys that will be added to the vendor hash in the JSON generated by the agent.
    private static final String PCF_INSTANCE_GUID_KEY = "cf_instance_guid";
    private static final String PCF_INSTANCE_IP_KEY = "cf_instance_ip";
    private static final String PCF_MEMORY_LIMIT_KEY = "memory_limit";

    @Override
    public PcfData getData() {
        // Fail fast optimization: short-circuit once we know that a previous environment variable is null
        String cfInstanceGuid = getPcfValue(CF_INSTANCE_GUID_ENV);
        String cfInstanceIp = (cfInstanceGuid == null) ? null : getPcfValue(CF_INSTANCE_IP_ENV);
        String memoryLimit = (cfInstanceIp == null) ? null : getPcfValue(MEMORY_LIMIT_ENV);

        // not on PCF
        if (cfInstanceGuid == null || cfInstanceIp == null || memoryLimit == null) {
            return PcfData.EMPTY_DATA;
        }

        if (cloudUtility.isInvalidValue(cfInstanceGuid) || cloudUtility.isInvalidValue(cfInstanceIp)
                || cloudUtility.isInvalidValue(memoryLimit)) {
            Agent.LOG.log(Level.WARNING, "Failed to validate PCF value");
            recordPcfError();
            return PcfData.EMPTY_DATA;
        }

        PcfData data = new PcfData(cfInstanceGuid, cfInstanceIp, memoryLimit);
        Agent.LOG.log(Level.FINEST, "Found {0}", data);
        return data;
    }

    protected String getPcfValue(String envVar) {
        // System.getenv(name) can throw SecurityException if a security manager exists or NPE if the name param is null
        try {
            return envVar == null ? null : System.getenv(envVar);
        } catch (SecurityException ex) {
            Agent.LOG.log(Level.FINEST, MessageFormat.format("Error occurred trying to get PCF value {0}", envVar));
            recordPcfError();
        }
        return null;
    }

    private void recordPcfError() {
        cloudUtility.recordError(MetricNames.SUPPORTABILITY_PCF_ERROR);
    }

    protected static class PcfData implements CloudData {
        String cfInstanceGuid;
        String cfInstanceIp;
        String memoryLimit;

        static final PcfData EMPTY_DATA = new PcfData();

        private PcfData() {
            cfInstanceGuid = null;
            cfInstanceIp = null;
            memoryLimit = null;
        }

        protected PcfData(String guid, String ip, String memory) {
            cfInstanceGuid = guid;
            cfInstanceIp = ip;
            memoryLimit = memory;
        }

        public String getInstanceGuid() {
            return cfInstanceGuid;
        }

        public String getInstanceIp() {
            return cfInstanceIp;
        }

        public String getMemoryLimit() {
            return memoryLimit;
        }

        @Override
        public Map<String, String> getValueMap() {
            Map<String, String> pcf = new HashMap<>();

            if (cfInstanceGuid == null || cfInstanceIp == null || memoryLimit == null) {
                return pcf;
            } else {
                pcf.put(PCF_INSTANCE_GUID_KEY, cfInstanceGuid);
                pcf.put(PCF_INSTANCE_IP_KEY, cfInstanceIp);
                pcf.put(PCF_MEMORY_LIMIT_KEY, memoryLimit);
            }
            return pcf;
        }

        @Override
        public String getProvider() {
            return PROVIDER;
        }

        @Override
        public boolean isEmpty() {
            return this == EMPTY_DATA;
        }

        @Override
        public String toString() {
            return "PcfData{" +
                    "cfInstanceGuid='" + cfInstanceGuid + '\'' +
                    ", cfInstanceIp='" + cfInstanceIp + '\'' +
                    ", memoryLimit='" + memoryLimit + '\'' +
                    '}';
        }
    }

}
