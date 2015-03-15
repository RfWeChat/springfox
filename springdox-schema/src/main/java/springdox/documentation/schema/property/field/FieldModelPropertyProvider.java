package springdox.documentation.schema.property.field;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.members.ResolvedField;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import springdox.documentation.builders.ModelPropertyBuilder;
import springdox.documentation.schema.Annotations;
import springdox.documentation.schema.Collections;
import springdox.documentation.schema.ModelProperty;
import springdox.documentation.schema.ModelRef;
import springdox.documentation.schema.TypeNameExtractor;
import springdox.documentation.schema.configuration.ObjectMapperConfigured;
import springdox.documentation.schema.plugins.SchemaPluginsManager;
import springdox.documentation.schema.property.BeanPropertyDefinitions;
import springdox.documentation.schema.property.BeanPropertyNamingStrategy;
import springdox.documentation.schema.property.provider.ModelPropertiesProvider;
import springdox.documentation.spi.schema.contexts.ModelContext;
import springdox.documentation.spi.schema.contexts.ModelPropertyContext;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.Lists.*;

@Component
public class FieldModelPropertyProvider implements ModelPropertiesProvider {

  private final FieldProvider fieldProvider;
  private final BeanPropertyNamingStrategy namingStrategy;
  private final SchemaPluginsManager schemaPluginsManager;
  private final TypeNameExtractor typeNameExtractor;
  protected ObjectMapper objectMapper;

  @Autowired
  public FieldModelPropertyProvider(
          FieldProvider fieldProvider,
          BeanPropertyNamingStrategy namingStrategy,
          SchemaPluginsManager schemaPluginsManager,
          TypeNameExtractor typeNameExtractor) {

    this.fieldProvider = fieldProvider;
    this.namingStrategy = namingStrategy;
    this.schemaPluginsManager = schemaPluginsManager;
    this.typeNameExtractor = typeNameExtractor;
  }

  @VisibleForTesting
  List<ModelProperty> addSerializationCandidates(AnnotatedMember member, ResolvedField
          childField, Optional<BeanPropertyDefinition> jacksonProperty, ModelContext givenContext) {
    if (memberIsAField(member)) {
      if (Annotations.memberIsUnwrapped(member)) {
        return propertiesFor(childField.getType(), ModelContext.fromParent(givenContext, childField.getType()));
      } else {
        String fieldName = BeanPropertyDefinitions.name(jacksonProperty.get(), true, namingStrategy);
        return newArrayList(modelPropertyFrom(childField, fieldName, givenContext));
      }
    }
    return newArrayList();
  }

  private ModelProperty modelPropertyFrom(ResolvedField childField, String fieldName,
      ModelContext modelContext) {
    FieldModelProperty fieldModelProperty = new FieldModelProperty(fieldName, childField, modelContext
            .getAlternateTypeProvider());
    ModelPropertyBuilder propertyBuilder = new ModelPropertyBuilder()
            .name(fieldModelProperty.getName())
            .type(childField.getType())
            .qualifiedType(fieldModelProperty.qualifiedTypeName())
            .position(fieldModelProperty.position())
            .required(fieldModelProperty.isRequired())
            .description(fieldModelProperty.propertyDescription())
            .allowableValues(fieldModelProperty.allowableValues())
            .modelRef(modelRef(fieldModelProperty.getType(), ModelContext.fromParent(modelContext, fieldModelProperty.getType())));
    return schemaPluginsManager.property(new ModelPropertyContext(propertyBuilder,
            childField.getRawMember(), modelContext.getDocumentationType()));
  }

  @Override
  public List<ModelProperty> propertiesFor(ResolvedType type,
      ModelContext givenContext) {

    List<ModelProperty> serializationCandidates = newArrayList();
    BeanDescription beanDescription = beanDescription(type, givenContext);
    Map<String, BeanPropertyDefinition> propertyLookup = Maps.uniqueIndex(beanDescription.findProperties(),
            BeanPropertyDefinitions.beanPropertyByInternalName());

    for (ResolvedField childField : fieldProvider.in(type)) {
      if (propertyLookup.containsKey(childField.getName())) {
        BeanPropertyDefinition propertyDefinition = propertyLookup.get(childField.getName());
        Optional<BeanPropertyDefinition> jacksonProperty
                = BeanPropertyDefinitions.jacksonPropertyWithSameInternalName(beanDescription, propertyDefinition);
        AnnotatedMember member = propertyDefinition.getPrimaryMember();
        serializationCandidates.addAll(newArrayList(addSerializationCandidates(member, childField, jacksonProperty,
                givenContext)));
      }
    }
    return serializationCandidates;
  }
  
  private ModelRef modelRef(ResolvedType type, ModelContext modelContext) {
    if (Collections.isContainerType(type)) {
      ResolvedType collectionElementType = Collections.collectionElementType(type);
      String elementTypeName = typeNameExtractor.typeName(ModelContext.fromParent(modelContext, collectionElementType));
      return new ModelRef(Collections.containerType(type), elementTypeName);
    }
    if (springdox.documentation.schema.Maps.isMapType(type)) {
      String elementTypeName = typeNameExtractor.typeName(ModelContext.fromParent(modelContext, springdox.documentation.schema.Maps.mapValueType(type)));
      return new ModelRef("Map", elementTypeName, true);
    }
    String typeName = typeNameExtractor.typeName(modelContext);
    return new ModelRef(typeName);
  }

  private BeanDescription beanDescription(ResolvedType type, ModelContext context) {
    if (context.isReturnType()) {
      SerializationConfig serializationConfig = objectMapper.getSerializationConfig();
      return serializationConfig.introspect(TypeFactory.defaultInstance()
              .constructType(type.getErasedType()));
    } else {
      DeserializationConfig serializationConfig = objectMapper.getDeserializationConfig();
      return serializationConfig.introspect(TypeFactory.defaultInstance()
              .constructType(type.getErasedType()));
    }
  }

  public void onApplicationEvent(ObjectMapperConfigured event) {
    this.objectMapper = event.getObjectMapper();
  }

  protected boolean memberIsAField(AnnotatedMember member) {
    return member != null
            && member.getMember() != null
            && Field.class.isAssignableFrom(member.getMember().getClass());
  }

}