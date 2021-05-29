package org.openremote.test.model

import com.fasterxml.jackson.databind.node.ObjectNode
import org.openremote.agent.protocol.http.HTTPAgent
import org.openremote.agent.protocol.simulator.SimulatorAgent
import org.openremote.agent.protocol.velbus.VelbusTCPAgent
import org.openremote.manager.asset.AssetModelService
import org.openremote.model.asset.Asset
import org.openremote.model.asset.AssetModelResource
import org.openremote.model.asset.agent.AgentLink
import org.openremote.model.asset.impl.GroupAsset
import org.openremote.model.asset.impl.LightAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.MetaItem
import org.openremote.model.rules.AssetState
import org.openremote.model.asset.AssetTypeInfo
import org.openremote.model.util.AssetModelUtil
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.SubStringValueFilter
import org.openremote.model.value.ValueConstraint
import org.openremote.model.value.ValueFilter
import org.openremote.model.value.ValueType
import org.openremote.model.value.Values
import org.openremote.model.value.impl.ColourRGB
import org.openremote.test.ManagerContainerTrait
import org.openremote.test.protocol.http.HTTPServerTestAgent
import spock.lang.Shared
import spock.lang.Specification

import static org.openremote.model.Constants.MASTER_REALM
import static org.openremote.model.value.ValueType.BIG_NUMBER

// TODO: Define new asset model tests (setValue - equality checking etc.)
class AssetModelTest extends Specification implements ManagerContainerTrait {

    @Shared
    static AssetModelResource assetModelResource

    def setupSpec() {
        def container = startContainer(defaultConfig(), defaultServices())
        def assetModelService = container.getService(AssetModelService.class)
        assetModelResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM).proxy(AssetModelResource.class)
    }

    def "Retrieving all asset model info"() {

        when: "an asset info is serialised"
        def thingAssetInfo = AssetModelUtil.getAssetInfo(ThingAsset.class).orElse(null)
        def thingAssetInfoStr = Values.asJSON(thingAssetInfo)

        then: "it should contain the right information"
        thingAssetInfoStr.isPresent()

        when: "the JSON representation is deserialised"
        def thingAssetInfo2 = Values.parse(thingAssetInfoStr.get(), AssetTypeInfo.class)

        then: "it should have been successfully deserialised"
        thingAssetInfo2.isPresent()
        thingAssetInfo2.get().getAssetDescriptor().type == ThingAsset.class
        thingAssetInfo2.get().attributeDescriptors.find { (it == Asset.LOCATION) } != null
        !thingAssetInfo2.get().attributeDescriptors.find { (it == Asset.LOCATION) }.optional
        thingAssetInfo2.get().attributeDescriptors.find { (it == Asset.LOCATION) }.type == ValueType.GEO_JSON_POINT

        when: "the asset type value descriptor is retrieved"
        def assetValueType = AssetModelUtil.getValueDescriptor(ValueType.ASSET_TYPE.getName())

        then: "it should contain an allowed values constraint with all asset types listed"
        assetValueType.isPresent()
        assetValueType.get().constraints != null
        assetValueType.get().constraints.find {it instanceof ValueConstraint.AllowedValues} as ValueConstraint.AllowedValues != null
        (assetValueType.get().constraints.find {it instanceof ValueConstraint.AllowedValues} as ValueConstraint.AllowedValues).allowedValues.length == AssetModelUtil.getAssetInfos().length
        (assetValueType.get().constraints.find {it instanceof ValueConstraint.AllowedValues} as ValueConstraint.AllowedValues).allowedValues.any { (it == GroupAsset.class.getSimpleName()) }

        when: "All asset model infos are retrieved"
        def assetInfos = assetModelResource.getAssetInfos(null, null, null);

        then: "the asset model infos should be available"
        assetInfos.size() > 0
        assetInfos.size() == AssetModelUtil.assetTypeMap.size()
        def velbusTcpAgent = assetInfos.find {it.assetDescriptor.type == VelbusTCPAgent.class}
        velbusTcpAgent != null
        velbusTcpAgent.attributeDescriptors.any {it == VelbusTCPAgent.VELBUS_HOST && !it.optional}
        velbusTcpAgent.attributeDescriptors.any {it == VelbusTCPAgent.VELBUS_PORT && !it.optional}
    }

    def "Retrieving a specific asset model info"() {
        when: "The Thing Asset model info is retrieved"
        def thingAssetInfo = assetModelResource.getAssetInfo(null, null, ThingAsset.DESCRIPTOR.name)

        then: "the asset model should be available"
        thingAssetInfo != null
        thingAssetInfo.assetDescriptor != null
        thingAssetInfo.attributeDescriptors != null
        thingAssetInfo.metaItemDescriptors != null
        thingAssetInfo.valueDescriptors != null
        thingAssetInfo.attributeDescriptors.contains(Asset.LOCATION)
        thingAssetInfo.metaItemDescriptors.contains(MetaItemType.AGENT_LINK)
        AssetModelUtil.getAssetDescriptor(ThingAsset.class) != null
        AssetModelUtil.getAgentDescriptor(SimulatorAgent.class) != null

        and: "the test asset model provider should have registered test agents and assets"
        AssetModelUtil.getAgentDescriptor(HTTPServerTestAgent.DESCRIPTOR.name).isPresent()
    }

    def "Serialize/Deserialize asset model"() {
        given: "An asset"
        def asset = new LightAsset("Test light")
            .setRealm(MASTER_REALM)
            .setTemperature(100I)
            .setColourRGB(new ColourRGB(50, 100, 200))
            .addAttributes(
                new Attribute<>("testAttribute", BIG_NUMBER, 100.5, System.currentTimeMillis())
                    .addOrReplaceMeta(
                        new MetaItem<>(MetaItemType.AGENT_LINK, new HTTPAgent.HttpAgentLink("http_agent_id")
                            .setPath("test_path")
                            .setPagingMode(true))
                    )
            )

        asset.getAttribute(LightAsset.COLOUR_RGB).ifPresent({
            it.addOrReplaceMeta(
                new MetaItem<>(MetaItemType.AGENT_LINK, new AgentLink.Default("agent_id")
                    .setValueFilters(
                        [new SubStringValueFilter(0,10)] as ValueFilter[]
                    )
                )
            )
        })

        expect: "the attributes to match the set values"
        asset.getTemperature().orElse(null) == 100I
        asset.getColourRGB().map{it.getR()}.orElse(null) == 50I
        asset.getColourRGB().map{it.getG()}.orElse(null) == 100I
        asset.getColourRGB().map{it.getB()}.orElse(null) == 200I

        when: "the asset is serialised using default object mapper"
        def assetStr = Values.asJSON(asset).orElse(null)

        then: "the string should be valid JSON"
        def assetObjectNode = Values.parse(assetStr, ObjectNode.class).get()
        assetObjectNode.get("name").asText() == "Test light"
        assetObjectNode.get("attributes").get("colourRGB").get("timestamp") == null
        assetObjectNode.get("attributes").get("colourRGB").get("meta").get(MetaItemType.AGENT_LINK.name).isObject()
        assetObjectNode.get("attributes").get("colourRGB").get("meta").get(MetaItemType.AGENT_LINK.name).get("id").asText() == "agent_id"
        assetObjectNode.get("attributes").get("colourRGB").get("meta").get(MetaItemType.AGENT_LINK.name).get("type").asText() == AgentLink.Default.class.getSimpleName()
        assetObjectNode.get("attributes").get("testAttribute").get("meta").get(MetaItemType.AGENT_LINK.name).isObject()
        assetObjectNode.get("attributes").get("testAttribute").get("value").decimalValue() == 100.5
        assetObjectNode.get("attributes").get("testAttribute").get("meta").get(MetaItemType.AGENT_LINK.name).get("id").asText() == "http_agent_id"
        assetObjectNode.get("attributes").get("testAttribute").get("meta").get(MetaItemType.AGENT_LINK.name).get("type").asText() == HTTPAgent.HttpAgentLink.class.getSimpleName()

        when: "the asset is deserialized"
        def asset2 = Values.parse(assetStr, LightAsset.class).orElse(null)

        then: "it should match the original"
        asset.getName() == asset2.getName()
        asset2.getType() == asset.getType()
        asset2.getTemperature().orElse(null) == asset.getTemperature().orElse(null)
        asset2.getColourRGB().map{it.getR()}.orElse(null) == asset.getColourRGB().map{it.getR()}.orElse(null)
        asset2.getColourRGB().map{it.getG()}.orElse(null) == asset.getColourRGB().map{it.getG()}.orElse(null)
        asset2.getColourRGB().map{it.getB()}.orElse(null) == asset.getColourRGB().map{it.getB()}.orElse(null)
        asset2.getAttribute("testAttribute", BIG_NUMBER.type).flatMap{it.getMetaValue(MetaItemType.AGENT_LINK)}.orElse(null) instanceof HTTPAgent.HttpAgentLink
        asset2.getAttribute("testAttribute", BIG_NUMBER.type).flatMap{it.getMetaValue(MetaItemType.AGENT_LINK)}.map{(HTTPAgent.HttpAgentLink)it}.flatMap{it.path}.orElse("") == "test_path"
        asset2.getAttribute("testAttribute", BIG_NUMBER.type).flatMap{it.getMetaValue(MetaItemType.AGENT_LINK)}.map{(HTTPAgent.HttpAgentLink)it}.flatMap{it.pagingMode}.orElse(false)

        when: "an attribute is cloned"
        def attribute = asset2.getAttribute(LightAsset.COLOUR_RGB).get()
        def clonedAttribute = Values.clone(attribute)

        then: "the cloned attribute should match the source"
        clonedAttribute.getName() == attribute.getName()
        clonedAttribute.getValue().orElse(null) == attribute.getValue().orElse(null)
        clonedAttribute.getMeta() == attribute.getMeta()

        when: "an asset state is serialized"
        def assetState = new AssetState(asset2, attribute, null)
        def assetStateStr = Values.asJSON(assetState).orElse(null)

        then: "it should look as expected"
        def assetStateObjectNode = Values.parse(assetStateStr, ObjectNode.class).get()
        assetStateObjectNode.get("name").asText() == LightAsset.COLOUR_RGB.name
        assetStateObjectNode.get("value").isTextual()
        assetStateObjectNode.get("value").asText() == "#3264c8"
    }
}
