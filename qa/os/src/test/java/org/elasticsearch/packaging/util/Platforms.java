/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.packaging.util;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.elasticsearch.packaging.util.FileUtils.slurp;

public class Platforms {

    public static final PlatformAction NO_ACTION = () -> {};

    public static String getOsRelease() {
        if (OS.current() == OS.LINUX) {
            return slurp(Paths.get("/etc/os-release"));
        } else {
            throw new RuntimeException("os-release is only supported on linux");
        }
    }

    /**
     * Essentially a Runnable, but we make the distinction so it's more clear that these are synchronous
     */
    @FunctionalInterface
    public interface PlatformAction {
        void run() throws Exception;
    }

    public enum OS {
        WINDOWS,
        LINUX,
        DARWIN;

        public static OS current() {
            String platform = System.getProperty("os.name");
            if (platform.toLowerCase().startsWith("windows")) {
                return OS.WINDOWS;
            }
            if (platform.toLowerCase().startsWith("linux")) {
                return OS.LINUX;
            }
            if (platform.toLowerCase().startsWith("mac")) {
                return OS.DARWIN;
            }
            throw new IllegalStateException("Can't determine platform from: " + platform);
        }
    }

    public static class OsConditional {

        private final Map<OS, PlatformAction> conditions = new HashMap<>();

        public OsConditional onWindows(PlatformAction action) {
            conditions.put(OS.WINDOWS, action);
            return this;
        }

        public OsConditional onDarwin(PlatformAction action) {
            conditions.put(OS.DARWIN, action);
            return this;
        }

        public OsConditional noDarwinTest() {
            onDarwin(() -> {
                throw new AssertionError("This test doesn't run on Darwin");
            });
            return this;
        }

        public OsConditional onLinux(PlatformAction action) {
            conditions.put(OS.LINUX, action);
            return this;
        }

        public void run() throws Exception {
            HashSet<OS> missingOS = new HashSet<>(Arrays.asList(OS.values()));
            missingOS.removeAll(conditions.keySet());
            if (missingOS.isEmpty() == false) {
                throw new IllegalStateException("No condition specified for " + missingOS);
            }
            conditions.get(OS.current()).run();
        }

        public static OsConditional conditional() {
            return new OsConditional();
        }
    }

    public enum PackageManager {
        RPM,
        DPKG,
        NO_PACKAGE_MANAGER;

        public static PackageManager current() {
            Shell sh = new Shell();
            if (OS.current() == OS.WINDOWS) {
                return NO_PACKAGE_MANAGER;
            }
            if (sh.runIgnoreExitCode("which rpm").isSuccess()) {
                return RPM;
            }
            if (sh.runIgnoreExitCode("which dpkg").isSuccess()) {
                return DPKG;
            }
            return NO_PACKAGE_MANAGER;
        }
    }

    public static class PackageManagerConditional {
        private final Map<PackageManager, PlatformAction> conditions = new HashMap<>();

        public PackageManagerConditional onRPM(PlatformAction action) {
            conditions.put(PackageManager.RPM, action);
            return this;
        }

        public PackageManagerConditional onDPKG(PlatformAction action) {
            conditions.put(PackageManager.DPKG, action);
            return this;
        }

        public PackageManagerConditional onNone(PlatformAction action) {
            conditions.put(PackageManager.NO_PACKAGE_MANAGER, action);
            return this;
        }

        public void run() throws Exception {
            HashSet<PackageManager> missingPackageManager = new HashSet<>(Arrays.asList(PackageManager.values()));
            missingPackageManager.removeAll(conditions.keySet());
            if (missingPackageManager.isEmpty() == false) {
                throw new IllegalStateException("No condition specified for " + missingPackageManager);
            }
            conditions.get(PackageManager.current()).run();
        }

        public static PackageManagerConditional condition() {
            return new PackageManagerConditional();
        }
    }

    public enum ServiceManager {
        SYSTEMD,
        SYSVINIT,
        NO_SERVICE_MANAGER;

        public static ServiceManager current() {
            Shell sh = new Shell();
            if (OS.current() == OS.WINDOWS) {
                return NO_SERVICE_MANAGER;
            }
            if (sh.runIgnoreExitCode("which systemctl").isSuccess()) {
                return SYSTEMD;
            }
            if (sh.runIgnoreExitCode("which service").isSuccess()) {
                return SYSVINIT;
            }
            return NO_SERVICE_MANAGER;
        }
    }

    public static class ServiceManagerConditional {
        private final Map<ServiceManager, PlatformAction> conditions = new HashMap<>();

        public ServiceManagerConditional onSystemd(PlatformAction action) {
            conditions.put(ServiceManager.SYSTEMD, action);
            return this;
        }

        public ServiceManagerConditional onSysVInit(PlatformAction action) {
            conditions.put(ServiceManager.SYSVINIT, action);
            return this;
        }

        public ServiceManagerConditional onNone(PlatformAction action) {
            conditions.put(ServiceManager.NO_SERVICE_MANAGER, action);
            return this;
        }

        public void run() throws Exception {
            HashSet<ServiceManager> missingServiceManager = new HashSet<>(Arrays.asList(ServiceManager.values()));
            missingServiceManager.removeAll(conditions.keySet());
            if (missingServiceManager.isEmpty() == false) {
                throw new IllegalStateException("No condition specified for " + missingServiceManager);
            }
            conditions.get(ServiceManager.current()).run();
        }

        public static ServiceManagerConditional conditional() {
            return new ServiceManagerConditional();
        }
    }
}
