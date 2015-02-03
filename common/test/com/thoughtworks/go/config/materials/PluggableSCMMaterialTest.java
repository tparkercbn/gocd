package com.thoughtworks.go.config.materials;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.domain.MaterialRevision;
import com.thoughtworks.go.domain.config.*;
import com.thoughtworks.go.domain.materials.MatchedRevision;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.domain.materials.Modifications;
import com.thoughtworks.go.domain.materials.scm.PluggableSCMMaterialInstance;
import com.thoughtworks.go.domain.materials.scm.PluggableSCMMaterialRevision;
import com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother;
import com.thoughtworks.go.domain.scm.SCM;
import com.thoughtworks.go.domain.scm.SCMMother;
import com.thoughtworks.go.helper.MaterialsMother;
import com.thoughtworks.go.plugin.access.scm.SCMConfigurations;
import com.thoughtworks.go.plugin.access.scm.SCMMetadataStore;
import com.thoughtworks.go.security.GoCipher;
import com.thoughtworks.go.util.CachedDigestUtils;
import com.thoughtworks.go.util.command.EnvironmentVariableContext;
import com.thoughtworks.go.util.json.JsonHelper;
import com.thoughtworks.go.util.json.JsonMap;
import org.junit.Test;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class PluggableSCMMaterialTest {
    @Test
    public void shouldCreatePluggableSCMMaterialInstance() {
        PluggableSCMMaterial material = MaterialsMother.pluggableSCMMaterial();
        PluggableSCMMaterialInstance materialInstance = (PluggableSCMMaterialInstance) material.createMaterialInstance();

        assertThat(materialInstance, is(notNullValue()));
        assertThat(materialInstance.getFlyweightName(), is(notNullValue()));
        assertThat(materialInstance.getConfiguration(), is(JsonHelper.toJsonString(material)));
    }

    @Test
    public void shouldGetMaterialInstanceType() {
        assertThat(new PluggableSCMMaterial().getInstanceType().equals(PluggableSCMMaterialInstance.class), is(true));
    }

    @Test
    public void shouldGetSqlCriteria() {
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", new Configuration(ConfigurationPropertyMother.create("k1", false, "v1")));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        Map<String, Object> criteria = material.getSqlCriteria();
        assertThat((String) criteria.get("type"), is(PluggableSCMMaterial.class.getSimpleName()));
        assertThat((String) criteria.get("fingerprint"), is(material.getFingerprint()));
    }

    @Test
    public void shouldGetFingerprintForMaterial() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        ConfigurationProperty k2 = ConfigurationPropertyMother.create("secure-key", true, "secure-value");
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", new Configuration(k1, k2));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        assertThat(material.getFingerprint(), is(CachedDigestUtils.sha256Hex("plugin-id=pluginid<|>k1=v1<|>secure-key=secure-value")));
    }

    @Test
    public void shouldGetDifferentFingerprintWhenPluginIdChanges() {
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "plugin-1", "version", new Configuration(ConfigurationPropertyMother.create("k1", false, "v1")));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        SCM anotherSCMConfig = SCMMother.create("scm-id", "scm-name", "plugin-2", "version", new Configuration(ConfigurationPropertyMother.create("k1", false, "v1")));
        PluggableSCMMaterial anotherMaterial = new PluggableSCMMaterial();
        anotherMaterial.setSCMConfig(anotherSCMConfig);

        assertThat(material.getFingerprint().equals(anotherMaterial.getFingerprint()), is(false));
    }

    @Test
    public void shouldGetDescription() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        assertThat(material.getDescription(), is("scm-name"));
    }

    @Test
    public void shouldGetDisplayName() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        assertThat(material.getDisplayName(), is("scm-name"));
    }

    @Test
    public void shouldTypeForDisplay() {
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        assertThat(material.getTypeForDisplay(), is("SCM"));
    }

    @Test
    public void shouldGetAttributesForXml() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        Map<String, Object> attributesForXml = material.getAttributesForXml();

        assertThat(attributesForXml.get("type").toString(), is(PluggableSCMMaterial.class.getSimpleName()));
        assertThat(attributesForXml.get("scmName").toString(), is("scm-name"));
    }

    @Test
    public void shouldConvertPluggableSCMMaterialToJsonFormatToBeStoredInDb() {
        GoCipher cipher = new GoCipher();
        ConfigurationProperty secureSCMProperty = new ConfigurationProperty(new ConfigurationKey("secure-key"), null, new EncryptedConfigurationValue("hnfcyX5dAvd82AWUyjfKCQ\u003d\u003d"), cipher);
        ConfigurationProperty scmProperty = new ConfigurationProperty(new ConfigurationKey("non-secure-key"), new ConfigurationValue("value"), null, cipher);
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "plugin-id", "1.0", new Configuration(secureSCMProperty, scmProperty));

        PluggableSCMMaterial pluggableSCMMaterial = new PluggableSCMMaterial();
        pluggableSCMMaterial.setSCMConfig(scmConfig);

        String json = JsonHelper.toJsonString(pluggableSCMMaterial);

        String expected = "{\"scm\":{\"plugin\":{\"id\":\"plugin-id\",\"version\":\"1.0\"},\"config\":[{\"configKey\":{\"name\":\"secure-key\"},\"encryptedConfigValue\":{\"value\":\"hnfcyX5dAvd82AWUyjfKCQ\\u003d\\u003d\"}},{\"configKey\":{\"name\":\"non-secure-key\"},\"configValue\":{\"value\":\"value\"}}]}}";
        assertThat(json, is(expected));
        assertThat(JsonHelper.fromJson(expected, PluggableSCMMaterial.class), is(pluggableSCMMaterial));
    }

    @Test
    public void shouldGetJsonRepresentationForPluggableSCMMaterial() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);
        material.setFolder("folder");
        JsonMap jsonMap = new JsonMap();
        material.toJson(jsonMap, new PluggableSCMMaterialRevision("rev123", new Date()));

        assertThat(jsonMap.hasEntry("scmType", "SCM"), is(true));
        assertThat(jsonMap.hasEntry("materialName", "scm-name"), is(true));
        assertThat(jsonMap.hasEntry("location", material.getUriForDisplay()), is(true));
        assertThat(jsonMap.hasEntry("folder", "folder"), is(true));
        assertThat(jsonMap.hasEntry("action", "Modified"), is(true));
    }

    @Test
    public void shouldGetEmailContentForPluggableSCMMaterial() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        StringBuilder content = new StringBuilder();
        Date date = new Date(1367472329111L);
        material.emailContent(content, new Modification(null, "comment", null, date, "rev123"));

        assertThat(content.toString(), is(String.format("SCM : scm-name\nrevision: rev123, completed on %s\ncomment", date.toString())));
    }

    @Test
    public void shouldReturnFalseForIsUsedInFetchArtifact() {
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        assertThat(material.isUsedInFetchArtifact(new PipelineConfig()), is(false));
    }

    @Test
    public void shouldReturnMatchedRevisionForPluggableSCMMaterial() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        Date timestamp = new Date();
        MatchedRevision matchedRevision = material.createMatchedRevision(new Modification("go", "comment", null, timestamp, "rev123"), "rev");

        assertThat(matchedRevision.getShortRevision(), is("rev123"));
        assertThat(matchedRevision.getLongRevision(), is("rev123"));
        assertThat(matchedRevision.getCheckinTime(), is(timestamp));
        assertThat(matchedRevision.getUser(), is("go"));
        assertThat(matchedRevision.getComment(), is("comment"));
    }

    @Test
    public void shouldGetNameFromSCMName() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        assertThat(material.getName().toString(), is("scm-name"));
    }

    @Test
    public void shouldPopulateEnvironmentContext() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        ConfigurationProperty k2 = ConfigurationPropertyMother.create("scm-secure", true, "value");
        SCM scmConfig = SCMMother.create("scm-id", "tw-dev", "pluginid", "version", new Configuration(k1, k2));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);
        material.setName(new CaseInsensitiveString("tw-dev:go-agent"));
        Modifications modifications = new Modifications(new Modification(null, null, null, new Date(), "revision-123"));
        EnvironmentVariableContext environmentVariableContext = new EnvironmentVariableContext();
        material.populateEnvironmentContext(environmentVariableContext, new MaterialRevision(material, modifications), null);

        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_K1"), is("v1"));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_SCM_SECURE"), is("value"));
        assertThat(environmentVariableContext.getPropertyForDisplay("GO_SCM_TW_DEV_GO_AGENT_SCM_SECURE"), is(EnvironmentVariableContext.EnvironmentVariable.MASK_VALUE));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_LABEL"), is("revision-123"));
    }

    @Test
    public void shouldPopulateEnvironmentContextWithEnvironmentVariablesCreatedOutOfAdditionalDataFromModification() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "tw-dev", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);
        material.setName(new CaseInsensitiveString("tw-dev:go-agent"));
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("MY_NEW_KEY", "my_value");
        Modification modification = new Modification("loser", "comment", "email", new Date(), "revision-123", JsonHelper.toJsonString(map));
        Modifications modifications = new Modifications(modification);

        EnvironmentVariableContext environmentVariableContext = new EnvironmentVariableContext();

        material.populateEnvironmentContext(environmentVariableContext, new MaterialRevision(material, modifications), null);

        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_LABEL"), is("revision-123"));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_K1"), is("v1"));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_MY_NEW_KEY"), is("my_value"));
    }

    @Test
    public void shouldMarkEnvironmentContextCreatedForAdditionalDataAsSecureIfTheValueContainsAnySpecialCharacters() throws UnsupportedEncodingException {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        ConfigurationProperty k2 = ConfigurationPropertyMother.create("k2", true, "!secure_value:with_special_chars");
        SCM scmConfig = SCMMother.create("scm-id", "tw-dev", "pluginid", "version", new Configuration(k1, k2));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);
        material.setName(new CaseInsensitiveString("tw-dev:go-agent"));
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("ADDITIONAL_DATA_ONE", "foobar:!secure_value:with_special_chars");
        map.put("ADDITIONAL_DATA_URL_ENCODED", "something:%21secure_value%3Awith_special_chars");
        map.put("ADDITIONAL_DATA_TWO", "foobar:secure_value_with_regular_chars");
        Modification modification = new Modification("loser", "comment", "email", new Date(), "revision-123", JsonHelper.toJsonString(map));
        Modifications modifications = new Modifications(modification);

        EnvironmentVariableContext environmentVariableContext = new EnvironmentVariableContext();

        material.populateEnvironmentContext(environmentVariableContext, new MaterialRevision(material, modifications), null);

        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_LABEL"), is("revision-123"));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_K1"), is("v1"));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_K2"), is("!secure_value:with_special_chars"));
        assertThat(environmentVariableContext.getPropertyForDisplay("GO_SCM_TW_DEV_GO_AGENT_K2"), is(EnvironmentVariableContext.EnvironmentVariable.MASK_VALUE));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_ADDITIONAL_DATA_ONE"), is("foobar:!secure_value:with_special_chars"));
        assertThat(environmentVariableContext.getPropertyForDisplay("GO_SCM_TW_DEV_GO_AGENT_ADDITIONAL_DATA_ONE"), is("foobar:!secure_value:with_special_chars"));
        assertThat(environmentVariableContext.getPropertyForDisplay("GO_SCM_TW_DEV_GO_AGENT_ADDITIONAL_DATA_TWO"), is("foobar:secure_value_with_regular_chars"));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_ADDITIONAL_DATA_URL_ENCODED"), is("something:%21secure_value%3Awith_special_chars"));
        assertThat(environmentVariableContext.getPropertyForDisplay("GO_SCM_TW_DEV_GO_AGENT_ADDITIONAL_DATA_URL_ENCODED"), is(EnvironmentVariableContext.EnvironmentVariable.MASK_VALUE));
    }

    @Test
    public void shouldNotThrowUpWhenAdditionalDataIsNull() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "tw-dev", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);
        material.setName(new CaseInsensitiveString("tw-dev:go-agent"));
        Modifications modifications = new Modifications(new Modification("loser", "comment", "email", new Date(), "revision-123", null));
        EnvironmentVariableContext environmentVariableContext = new EnvironmentVariableContext();

        material.populateEnvironmentContext(environmentVariableContext, new MaterialRevision(material, modifications), null);

        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_LABEL"), is("revision-123"));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_K1"), is("v1"));
    }

    @Test
    public void shouldNotThrowUpWhenAdditionalDataIsRandomJunkAndNotJSON() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        SCM scmConfig = SCMMother.create("scm-id", "tw-dev", "pluginid", "version", new Configuration(k1));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);
        material.setName(new CaseInsensitiveString("tw-dev:go-agent"));
        Modifications modifications = new Modifications(new Modification("loser", "comment", "email", new Date(), "revision-123", "salkdfjdsa-jjgkj!!!vcxknbvkjk"));
        EnvironmentVariableContext environmentVariableContext = new EnvironmentVariableContext();

        material.populateEnvironmentContext(environmentVariableContext, new MaterialRevision(material, modifications), null);

        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_LABEL"), is("revision-123"));
        assertThat(environmentVariableContext.getProperty("GO_SCM_TW_DEV_GO_AGENT_K1"), is("v1"));
    }

    @Test
    public void shouldGetUriForDisplay() {
        SCMMetadataStore.getInstance().addMetadataFor("some-plugin", new SCMConfigurations(), null);

        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "scm-v1");
        ConfigurationProperty k2 = ConfigurationPropertyMother.create("k2", false, "scm-v2");
        Configuration configuration = new Configuration(k1, k2);
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "some-plugin", "version", configuration);
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        assertThat(material.getUriForDisplay(), is("SCM: [k1=scm-v1, k2=scm-v2]"));
    }

    @Test
    public void shouldGetUriForDisplayNameIfNameIsNull() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "scm-v1");
        ConfigurationProperty k2 = ConfigurationPropertyMother.create("k2", false, "scm-v2");
        SCM scmConfig = SCMMother.create("scm-id", null, "pluginid", "version", new Configuration(k1, k2));
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        assertThat(material.getDisplayName(), is(material.getUriForDisplay()));
    }

    @Test
    public void shouldGetLongDescription() {
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "scm-v1");
        ConfigurationProperty k2 = ConfigurationPropertyMother.create("k2", false, "scm-v2");
        Configuration configuration = new Configuration(k1, k2);
        SCM scmConfig = SCMMother.create("scm-id", "scm-name", "pluginid", "version", configuration);
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setSCMConfig(scmConfig);

        assertThat(material.getLongDescription(), is(material.getUriForDisplay()));
    }

    @Test
    public void shouldPassEqualsCheckIfFingerprintIsSame() {
        PluggableSCMMaterial material1 = MaterialsMother.pluggableSCMMaterial();
        material1.setName(new CaseInsensitiveString("name1"));
        PluggableSCMMaterial material2 = MaterialsMother.pluggableSCMMaterial();
        material2.setName(new CaseInsensitiveString("name2"));

        assertThat(material1.equals(material2), is(true));
    }

    @Test
    public void shouldFailEqualsCheckIfFingerprintDiffers() {
        PluggableSCMMaterial material1 = MaterialsMother.pluggableSCMMaterial();
        material1.getScmConfig().getConfiguration().first().setConfigurationValue(new ConfigurationValue("new-url"));
        PluggableSCMMaterial material2 = MaterialsMother.pluggableSCMMaterial();

        assertThat(material1.equals(material2), is(false));
    }

    @Test
    public void shouldReturnSomethingMoreSaneForToString() throws Exception {
        PluggableSCMMaterial material = MaterialsMother.pluggableSCMMaterial();

        SCMMetadataStore.getInstance().addMetadataFor(material.getPluginId(), new SCMConfigurations(), null);

        assertThat(material.toString(), is("'PluggableSCMMaterial{SCM: [k1=v1, k2=v2]}'"));
    }

    @Test
    public void shouldReturnNameAsNullIfSCMConfigIsNotSet() {
        assertThat(new PluggableSCMMaterial().getName(), is(nullValue()));
    }

    @Test
    public void shouldNotCalculateFingerprintWhenAvailable() {
        String fingerprint = "fingerprint";
        SCM scmConfig = mock(SCM.class);
        PluggableSCMMaterial pluggableSCMMaterial = new PluggableSCMMaterial();
        pluggableSCMMaterial.setSCMConfig(scmConfig);
        pluggableSCMMaterial.setFingerprint(fingerprint);

        assertThat(pluggableSCMMaterial.getFingerprint(), is(fingerprint));
        verify(scmConfig, never()).getFingerprint();
    }

    @Test
    public void shouldTakeValueOfIsAutoUpdateFromSCMConfig() throws Exception {
        PluggableSCMMaterial material = MaterialsMother.pluggableSCMMaterial();

        material.getScmConfig().setAutoUpdate(true);
        assertThat(material.isAutoUpdate(), is(true));

        material.getScmConfig().setAutoUpdate(false);
        assertThat(material.isAutoUpdate(), is(false));
    }

    @Test
    public void shouldReturnWorkingDirectoryCorrectly() {
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        material.setFolder("dest");
        String baseFolder = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        String workingFolder = new File(baseFolder, "dest").getAbsolutePath();
        assertThat(material.workingDirectory(new File(baseFolder)).getAbsolutePath(), is(workingFolder));
        material.setFolder(null);
        assertThat(material.workingDirectory(new File(baseFolder)).getAbsolutePath(), is(baseFolder));
    }
}