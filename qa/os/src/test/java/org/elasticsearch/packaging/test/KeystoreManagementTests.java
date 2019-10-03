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

package org.elasticsearch.packaging.test;

import org.elasticsearch.packaging.util.Distribution;
import org.elasticsearch.packaging.util.FileUtils;
import org.elasticsearch.packaging.util.Installation;
import org.elasticsearch.packaging.util.Platforms;
import org.elasticsearch.packaging.util.ServerUtils;
import org.elasticsearch.packaging.util.Shell;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.elasticsearch.packaging.util.Archives.ARCHIVE_OWNER;
import static org.elasticsearch.packaging.util.Archives.installArchive;
import static org.elasticsearch.packaging.util.Archives.verifyArchiveInstallation;
import static org.elasticsearch.packaging.util.FileMatcher.Fileness.File;
import static org.elasticsearch.packaging.util.FileMatcher.file;
import static org.elasticsearch.packaging.util.FileMatcher.p660;
import static org.elasticsearch.packaging.util.FileUtils.rm;
import static org.elasticsearch.packaging.util.Packages.assertInstalled;
import static org.elasticsearch.packaging.util.Packages.assertRemoved;
import static org.elasticsearch.packaging.util.Packages.installPackage;
import static org.elasticsearch.packaging.util.Packages.verifyPackageInstallation;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

public class KeystoreManagementTests extends PackagingTestCase {

    private static final String PASSWORD_ERROR_MESSAGE = "Provided keystore password was incorrect";

    private static final String LINE_SEP = System.lineSeparator();

    /** We need an initially installed package */
    public void test10InstallArchiveDistribution() throws Exception {
        assumeTrue(distribution().isArchive());

        installation = installArchive(distribution);
        verifyArchiveInstallation(installation, distribution());

        final Installation.Executables bin = installation.executables();
        Shell.Result r = sh.runIgnoreExitCode(bin.elasticsearchKeystore + " has-passwd");
        assertThat("has-passwd should fail", r.exitCode, not(is(0)));
        assertThat("has-passwd should fail", r.stderr, containsString("ERROR: Elasticsearch keystore not found"));
    }

    /** We need an initially installed package */
    public void test11InstallPackageDistribution() throws Exception {
        assumeTrue(distribution().isPackage());

        assertRemoved(distribution);
        installation = installPackage(distribution);
        assertInstalled(distribution);
        verifyPackageInstallation(installation, distribution, sh);

        final Installation.Executables bin = installation.executables();
        Shell.Result r = sh.runIgnoreExitCode(bin.elasticsearchKeystore + " has-passwd");
        assertThat("has-passwd should fail", r.exitCode, not(is(0)));
        assertThat("has-passwd should fail", r.stderr, containsString("ERROR: Keystore is not password-protected"));
    }

    public void test20CreateKeystoreManually() throws Exception {
        rmKeystoreIfExists();
        createKeystore();

        final Installation.Executables bin = installation.executables();
        verifyKeystorePermissions();

        Shell.Result r = sh.run(bin.elasticsearchKeystore + " list");
        assertThat(r.stdout, containsString("keystore.seed"));
    }

    public void test30AutoCreateKeystore() throws Exception {
        assumeTrue("RPMs and Debs install a keystore file", distribution.isArchive());
        rmKeystoreIfExists();

        awaitElasticsearchStartup(startElasticsearch());
        stopElasticsearch();

        verifyKeystorePermissions();

        final Installation.Executables bin = installation.executables();
        Shell.Result listing = sh.run(bin.elasticsearchKeystore + " list");
        assertThat(listing.stdout, containsString("keystore.seed"));
    }

    public void test40KeystorePasswordOnStandardInput() throws Exception {
        assumeTrue("packages will use systemd, which doesn't handle stdin",
            distribution.isArchive());
        assumeThat(installation, is(notNullValue()));

        String password = "^|<>\\&exit"; // code insertion on Windows

        rmKeystoreIfExists();
        createKeystore();
        setKeystorePassword(password);

        assertPasswordProtectedKeystore();

        awaitElasticsearchStartup(startElasticsearchStandardInputPassword(password));
        ServerUtils.runElasticsearchTests();
        stopElasticsearch();
    }

    public void test41WrongKeystorePasswordOnStandardInput() {
        assumeTrue("packages will use systemd, which doesn't handle stdin",
            distribution.isArchive());
        assumeThat(installation, is(notNullValue()));

        assertPasswordProtectedKeystore();

        Shell.Result result = startElasticsearchStandardInputPassword("wrong");
        assertElasticsearchFailure(result, PASSWORD_ERROR_MESSAGE);
    }

    public void test42KeystorePasswordOnTty() throws Exception {
        assumeTrue("expect command isn't on Windows",
            distribution.platform != Distribution.Platform.WINDOWS);
        assumeTrue("packages will use systemd, which doesn't handle stdin",
            distribution.isArchive());
        assumeThat(installation, is(notNullValue()));

        String password = "keystorepass";

        rmKeystoreIfExists();
        createKeystore();
        setKeystorePassword(password);

        assertPasswordProtectedKeystore();

        awaitElasticsearchStartup(startElasticsearchTtyPassword(password));
        ServerUtils.runElasticsearchTests();
        stopElasticsearch();
    }

    public void test43WrongKeystorePasswordOnTty() throws Exception {
        assumeTrue("expect command isn't on Windows",
            distribution.platform != Distribution.Platform.WINDOWS);
        assumeTrue("packages will use systemd, which doesn't handle stdin",
            distribution.isArchive());
        assumeThat(installation, is(notNullValue()));

        assertPasswordProtectedKeystore();

        Shell.Result result = startElasticsearchTtyPassword("wrong");
        // error will be on stdout for "expect"
        assertThat(result.stdout, containsString(PASSWORD_ERROR_MESSAGE));
    }

    public void test50KeystorePasswordFromFile() throws Exception {
        String password = "!@#$%^&*()|\\<>/?";
        Path esKeystorePassphraseFile = installation.config.resolve("eks");

        rmKeystoreIfExists();
        createKeystore();
        setKeystorePassword(password);

        assertPasswordProtectedKeystore();

        sh.getEnv().put("ES_KEYSTORE_PASSPHRASE_FILE", esKeystorePassphraseFile.toString());
        if (distribution.isPackage()) {
            sh.run("sudo systemctl set-environment ES_KEYSTORE_PASSPHRASE_FILE=$ES_KEYSTORE_PASSPHRASE_FILE");
        }

        Files.createFile(esKeystorePassphraseFile);
        Files.write(esKeystorePassphraseFile,
            (password + LINE_SEP).getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.WRITE);

        awaitElasticsearchStartup(startElasticsearch());
        ServerUtils.runElasticsearchTests();
        stopElasticsearch();
    }

    public void test51WrongKeystorePasswordFromFile() throws Exception {
        Path esKeystorePassphraseFile = installation.config.resolve("eks");

        assertPasswordProtectedKeystore();

        sh.getEnv().put("ES_KEYSTORE_PASSPHRASE_FILE", esKeystorePassphraseFile.toString());
        if (distribution.isPackage()) {
            sh.run("sudo systemctl set-environment ES_KEYSTORE_PASSPHRASE_FILE=$ES_KEYSTORE_PASSPHRASE_FILE");
        }

        if (Files.exists(esKeystorePassphraseFile)) {
            rm(esKeystorePassphraseFile);
        }

        Files.createFile(esKeystorePassphraseFile);
        Files.write(esKeystorePassphraseFile,
            ("wrongpassword" + LINE_SEP).getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.WRITE);

        Shell.Result result = startElasticsearch();
        assertElasticsearchFailure(result, PASSWORD_ERROR_MESSAGE);
    }

    private void createKeystore() throws Exception {
        Path keystore = installation.config("elasticsearch.keystore");
        final Installation.Executables bin = installation.executables();
        Platforms.OsConditional.conditional()
            .onLinux(() -> {
                selectOnPackaging(
                    () -> sh.run(bin.elasticsearchKeystore + " create"),
                    () -> sh.run("sudo -u " + ARCHIVE_OWNER + " " + bin.elasticsearchKeystore + " create"));
            })
            // this is a hack around the fact that we can't run a command in the same session as the same user but not as administrator.
            // the keystore ends up being owned by the Administrators group, so we manually set it to be owned by the vagrant user here.
            // from the server's perspective the permissions aren't really different, this is just to reflect what we'd expect in the tests.
            // when we run these commands as a role user we won't have to do this
            .onWindows(() -> sh.run(
                bin.elasticsearchKeystore + " create; " +
                    "$account = New-Object System.Security.Principal.NTAccount 'vagrant'; " +
                    "$acl = Get-Acl '" + keystore + "'; " +
                    "$acl.SetOwner($account); " +
                    "Set-Acl '" + keystore + "' $acl"
            ))
            .noDarwinTest()
            .run();
    }

    private void rmKeystoreIfExists() {
        Path keystore = installation.config("elasticsearch.keystore");
        if (Files.exists(keystore)) {
            FileUtils.rm(keystore);
        }
    }

    private void setKeystorePassword(String password) throws Exception {
        final Installation.Executables bin = installation.executables();

        // set the password by passing it to stdin twice
        Platforms.OsConditional.conditional()
            .onLinux(() ->
                selectOnPackaging(
                    () -> sh.run("( echo \'" + password + "\' ; echo \'" + password + "\' ) | " +
                        bin.elasticsearchKeystore + " passwd"),
                    () -> sh.run("( echo \'" + password + "\' ; echo \'" + password + "\' ) | " +
                        "sudo -u " + ARCHIVE_OWNER + " " + bin.elasticsearchKeystore + " passwd")
                )
            )
            .onWindows(() -> {
                sh.run("Invoke-Command -ScriptBlock {echo \'" + password + "\'; echo \'" + password + "\'} | "
                    + bin.elasticsearchKeystore + " passwd");
            })
            .noDarwinTest()
            .run();
    }

    private void assertPasswordProtectedKeystore() {
        Shell.Result r = sh.runIgnoreExitCode(installation.executables().elasticsearchKeystore.toString() + " has-passwd");
        assertThat("keystore should be password protected", r.exitCode, is(0));
    }

    @FunctionalInterface
    private interface ExceptionalRunnable {
        void run() throws Exception;
    }

    private static void selectOnPackaging(Platforms.PlatformAction forPackage, Platforms.PlatformAction forArchive) throws Exception {
        assertTrue("Distribution must be package or archive",
            distribution.isPackage() || distribution.isArchive());
        if (distribution.isPackage()) {
            forPackage.run();
        } else {
            forArchive.run();
        }
    }

    private void verifyKeystorePermissions() throws Exception {
        Path keystore = installation.config("elasticsearch.keystore");
        selectOnPackaging(
            () -> assertThat(keystore, file(File, "root", "elasticsearch", p660)),
            () -> assertThat(keystore, file(File, ARCHIVE_OWNER, ARCHIVE_OWNER, p660)));
    }
}
