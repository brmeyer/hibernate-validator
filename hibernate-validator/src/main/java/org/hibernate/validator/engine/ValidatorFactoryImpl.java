/*
* JBoss, Home of Professional Open Source
* Copyright 2010, Red Hat, Inc. and/or its affiliates, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.hibernate.validator.engine;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.validation.ConstraintValidatorFactory;
import javax.validation.MessageInterpolator;
import javax.validation.TraversableResolver;
import javax.validation.ValidationException;
import javax.validation.Validator;
import javax.validation.spi.ConfigurationState;

import org.hibernate.validator.HibernateValidatorConfiguration;
import org.hibernate.validator.HibernateValidatorContext;
import org.hibernate.validator.HibernateValidatorFactory;
import org.hibernate.validator.cfg.CascadeDef;
import org.hibernate.validator.cfg.ConfiguredConstraint;
import org.hibernate.validator.cfg.ConstraintMapping;
import org.hibernate.validator.cfg.MethodCascadeDef;
import org.hibernate.validator.metadata.AggregatedMethodMetaData;
import org.hibernate.validator.metadata.AnnotationIgnores;
import org.hibernate.validator.metadata.BeanMetaConstraint;
import org.hibernate.validator.metadata.BeanMetaDataCache;
import org.hibernate.validator.metadata.BeanMetaDataImpl;
import org.hibernate.validator.metadata.ConstraintDescriptorImpl;
import org.hibernate.validator.metadata.ConstraintHelper;
import org.hibernate.validator.metadata.ConstraintOrigin;
import org.hibernate.validator.metadata.MetaConstraint;
import org.hibernate.validator.metadata.MethodMetaData;
import org.hibernate.validator.metadata.ParameterMetaConstraint;
import org.hibernate.validator.metadata.ParameterMetaData;
import org.hibernate.validator.metadata.location.BeanConstraintLocation;
import org.hibernate.validator.metadata.location.MethodParameterConstraintLocation;
import org.hibernate.validator.util.ReflectionHelper;
import org.hibernate.validator.util.annotationfactory.AnnotationDescriptor;
import org.hibernate.validator.util.annotationfactory.AnnotationFactory;
import org.hibernate.validator.xml.XmlMappingParser;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static org.hibernate.validator.metadata.BeanMetaDataImpl.DEFAULT_PARAMETER_NAME_PREFIX;
import static org.hibernate.validator.util.CollectionHelper.newArrayList;
import static org.hibernate.validator.util.CollectionHelper.newHashMap;
import static org.hibernate.validator.util.CollectionHelper.newHashSet;

/**
 * Factory returning initialized {@code Validator} instances. This is Hibernate Validator's default
 * implementation of the {@code ValidatorFactory} interface.
 *
 * @author Emmanuel Bernard
 * @author Hardy Ferentschik
 * @author Gunnar Morling
 * @author Kevin Pollet - SERLI - (kevin.pollet@serli.com)
 */
public class ValidatorFactoryImpl implements HibernateValidatorFactory {

	private final MessageInterpolator messageInterpolator;
	private final TraversableResolver traversableResolver;
	private final ConstraintValidatorFactory constraintValidatorFactory;
	private final ConstraintHelper constraintHelper;
	private final boolean failFast;

	/**
	 * Used to cache the constraint meta data for validated entities
	 */
	private final BeanMetaDataCache beanMetaDataCache;

	public ValidatorFactoryImpl(ConfigurationState configurationState) {

		this.messageInterpolator = configurationState.getMessageInterpolator();
		this.constraintValidatorFactory = configurationState.getConstraintValidatorFactory();
		this.traversableResolver = configurationState.getTraversableResolver();
		this.constraintHelper = new ConstraintHelper();
		this.beanMetaDataCache = new BeanMetaDataCache();

		boolean tmpFailFast = false;

		// HV-302; don't load XmlMappingParser if not necessary
		if ( !configurationState.getMappingStreams().isEmpty() ) {
			initXmlConfiguration( configurationState.getMappingStreams() );
		}

		if ( configurationState instanceof ConfigurationImpl ) {
			ConfigurationImpl hibernateSpecificConfig = (ConfigurationImpl) configurationState;
			if ( hibernateSpecificConfig.getMapping() != null ) {
				initProgrammaticConfiguration( hibernateSpecificConfig.getMapping() );
			}
			// check whether fail fast is programmatically enabled
			tmpFailFast = hibernateSpecificConfig.getFailFast();
		}
		tmpFailFast = checkPropertiesForFailFast(
				configurationState, tmpFailFast
		);

		this.failFast = tmpFailFast;
	}

	public Validator getValidator() {
		return usingContext().getValidator();
	}

	public MessageInterpolator getMessageInterpolator() {
		return messageInterpolator;
	}

	public TraversableResolver getTraversableResolver() {
		return traversableResolver;
	}

	public ConstraintValidatorFactory getConstraintValidatorFactory() {
		return constraintValidatorFactory;
	}

	public <T> T unwrap(Class<T> type) {
		if ( HibernateValidatorFactory.class.equals( type ) ) {
			return type.cast( this );
		}
		throw new ValidationException( "Type " + type + " not supported" );
	}

	public HibernateValidatorContext usingContext() {
		return new ValidatorContextImpl(
				constraintValidatorFactory,
				messageInterpolator,
				traversableResolver,
				constraintHelper,
				beanMetaDataCache,
				failFast
		);
	}

	/**
	 * Reads the configuration from {@code mapping} and creates the appropriate meta-data structures.
	 *
	 * @param mapping The constraint configuration created via the programmatic API.
	 */
	private <T> void initProgrammaticConfiguration(ConstraintMapping mapping) {

		final Map<Class<?>, List<ConfiguredConstraint<?, BeanConstraintLocation>>> constraintsByType = mapping.getConstraintConfig();
		final Map<Class<?>, List<ConfiguredConstraint<?, MethodParameterConstraintLocation>>> methodConstraintsByType = mapping
				.getMethodConstraintConfig();
		final Map<Class<?>, List<CascadeDef>> cascadeConfigByType = mapping.getCascadeConfig();
		final Map<Class<?>, List<MethodCascadeDef>> methodCascadeConfigByType = mapping.getMethodCascadeConfig();

		for ( Class<?> clazz : mapping.getConfiguredClasses() ) {
			@SuppressWarnings("unchecked")
			Class<T> beanClass = (Class<T>) clazz;

			// for each configured entity we have to check whether any of the interfaces or super classes is configured
			// via the programmatic api as well
			List<Class<?>> classes = ReflectionHelper.computeClassHierarchy( beanClass, true );

			Map<Class<?>, List<BeanMetaConstraint<?>>> constraints = newHashMap();
			Set<AggregatedMethodMetaData.Builder> builders = newHashSet();
			Set<Member> cascadedMembers = new HashSet<Member>();

			for ( Class<?> classInHierarchy : classes ) {

				// if the programmatic config contains constraints for the class in the hierarchy create equivalent meta constraints
				List<ConfiguredConstraint<?, BeanConstraintLocation>> constraintsOfType = constraintsByType.get(
						classInHierarchy
				);
				if ( constraintsOfType != null ) {
					addProgrammaticConfiguredConstraints(
							constraintsOfType,
							beanClass,
							classInHierarchy,
							constraints
					);
				}

				// retrieve the method constraints
				List<ConfiguredConstraint<?, MethodParameterConstraintLocation>> methodConstraintsOfType = methodConstraintsByType
						.get( classInHierarchy );
				if ( methodConstraintsOfType != null ) {
					addProgrammaticConfiguredMethodConstraint(
							methodConstraintsOfType, beanClass, classInHierarchy, builders
					);
				}

				// retrieve the cascading members of the current class if applicable
				List<CascadeDef> cascadesOfType = cascadeConfigByType.get( classInHierarchy );
				if ( cascadesOfType != null ) {
					addProgrammaticConfiguredCascade( cascadesOfType, cascadedMembers );
				}

				// retrieve the cascading method return value and method parameter
				List<MethodCascadeDef> methodCascadesOfType = methodCascadeConfigByType.get( classInHierarchy );
				if ( methodCascadesOfType != null ) {
					addProgrammaticConfiguredMethodCascade( methodCascadesOfType, builders );
				}
			}

			// build the programmatic configured method metaData
			Set<AggregatedMethodMetaData> methodMetaDataMap = newHashSet();
			for ( AggregatedMethodMetaData.Builder oneBuilder : builders ) {
				methodMetaDataMap.add( oneBuilder.build() );
			}

			// create the bean metadata with the programmatic configured constraints and cascade
			BeanMetaDataImpl<T> metaData = new BeanMetaDataImpl<T>(
					beanClass,
					constraintHelper,
					mapping.getDefaultSequence( beanClass ),
					mapping.getDefaultGroupSequenceProvider( beanClass ),
					constraints,
					methodMetaDataMap,
					cascadedMembers,
					new AnnotationIgnores(),
					beanMetaDataCache
			);

			beanMetaDataCache.addBeanMetaData( beanClass, metaData );
		}
	}

	private <T> void initXmlConfiguration(Set<InputStream> mappingStreams) {

		XmlMappingParser mappingParser = new XmlMappingParser( constraintHelper );
		mappingParser.parse( mappingStreams );

		Set<Class<?>> xmlConfiguredClasses = mappingParser.getXmlConfiguredClasses();
		AnnotationIgnores annotationIgnores = mappingParser.getAnnotationIgnores();
		for ( Class<?> clazz : xmlConfiguredClasses ) {
			@SuppressWarnings("unchecked")
			Class<T> beanClass = (Class<T>) clazz;

			List<Class<?>> classes = ReflectionHelper.computeClassHierarchy( beanClass, true );
			Map<Class<?>, List<BeanMetaConstraint<?>>> constraints = newHashMap();
			Set<Member> cascadedMembers = new HashSet<Member>();
			// we need to collect all constraints which apply for a single class. Due to constraint inheritance
			// some constraints might be configured in super classes or interfaces. The xml configuration does not
			// imply any order so we have to check whether any of the super classes or interfaces of a given bean has
			// as well been configured via xml
			for ( Class<?> classInHierarchy : classes ) {
				if ( xmlConfiguredClasses.contains( classInHierarchy ) ) {
					addXmlConfiguredConstraints( mappingParser, beanClass, classInHierarchy, constraints );
					addXmlCascadedMember( mappingParser, classInHierarchy, cascadedMembers );
				}
			}

			BeanMetaDataImpl<T> metaData = new BeanMetaDataImpl<T>(
					beanClass,
					constraintHelper,
					mappingParser.getDefaultSequenceForClass( beanClass ),
					null,
					constraints,
					new HashSet<AggregatedMethodMetaData>(),
					cascadedMembers,
					annotationIgnores,
					beanMetaDataCache
			);

			beanMetaDataCache.addBeanMetaData( beanClass, metaData );
		}
	}

	@SuppressWarnings("unchecked")
	private <T, A extends Annotation> void addXmlConfiguredConstraints(XmlMappingParser mappingParser,
																	   Class<T> rootClass,
																	   Class<?> hierarchyClass, Map<Class<?>, List<BeanMetaConstraint<?>>> constraints) {
		for ( MetaConstraint<? extends Annotation> constraint : mappingParser.getConstraintsForClass( hierarchyClass ) ) {

			ConstraintOrigin definedIn = definedIn( rootClass, hierarchyClass );
			ConstraintDescriptorImpl<A> descriptor = new ConstraintDescriptorImpl<A>(
					(A) constraint.getDescriptor().getAnnotation(),
					constraintHelper,
					constraint.getElementType(),
					definedIn
			);

			//TODO GM: avoid this cast
			BeanMetaConstraint<? extends Annotation> asBeanMetaConstraint = (BeanMetaConstraint<? extends Annotation>) constraint;
			BeanMetaConstraint<A> newMetaConstraint = new BeanMetaConstraint<A>(
					descriptor,
					asBeanMetaConstraint.getLocation().getBeanClass(),
					asBeanMetaConstraint.getLocation().getMember()
			);

			addConstraintToMap( hierarchyClass, newMetaConstraint, constraints );
		}
	}

	@SuppressWarnings("unchecked")
	private <T, A extends Annotation> void addProgrammaticConfiguredConstraints(List<ConfiguredConstraint<?, BeanConstraintLocation>> definitions,
																				Class<T> rootClass, Class<?> hierarchyClass,
																				Map<Class<?>, List<BeanMetaConstraint<?>>> constraints) {
		for ( ConfiguredConstraint<?, BeanConstraintLocation> config : definitions ) {
			A annotation = (A) createAnnotationProxy( config );
			ConstraintOrigin definedIn = definedIn( rootClass, hierarchyClass );
			ConstraintDescriptorImpl<A> constraintDescriptor = new ConstraintDescriptorImpl<A>(
					annotation, constraintHelper, config.getLocation().getElementType(), definedIn
			);

			Member member = config.getLocation().getMember();

			BeanMetaConstraint<A> metaConstraint = new BeanMetaConstraint<A>(
					constraintDescriptor, config.getLocation().getBeanClass(), member
			);
			addConstraintToMap( hierarchyClass, metaConstraint, constraints );
		}
	}

	private <M extends MetaConstraint<? extends Annotation>> void addConstraintToMap(Class<?> hierarchyClass, M constraint, Map<Class<?>, List<M>> constraints) {

		List<M> constraintList = constraints.get( hierarchyClass );

		if ( constraintList == null ) {
			constraintList = new ArrayList<M>();
			constraints.put( hierarchyClass, constraintList );
		}

		constraintList.add( constraint );
	}

	private void addXmlCascadedMember(XmlMappingParser mappingParser,
									  Class<?> hierarchyClass,
									  Set<Member> cascadedMembers) {
		for ( Member m : mappingParser.getCascadedMembersForClass( hierarchyClass ) ) {
			cascadedMembers.add( m );
		}
	}

	private void addProgrammaticConfiguredCascade(List<CascadeDef> cascades,
												  Set<Member> cascadedMembers) {

		for ( CascadeDef cascade : cascades ) {
			Member m = ReflectionHelper.getMember(
					cascade.getBeanType(), cascade.getProperty(), cascade.getElementType()
			);
			cascadedMembers.add( m );
		}
	}

	private <T> void addProgrammaticConfiguredMethodConstraint(List<ConfiguredConstraint<?, MethodParameterConstraintLocation>> methodConstraints, Class<T> rootClass, Class<?> hierarchyClass, Set<AggregatedMethodMetaData.Builder> builders) {
		for ( ConfiguredConstraint<?, MethodParameterConstraintLocation> oneConstraint : methodConstraints ) {
			MethodMetaData methodMetaData = createMethodMetaData( oneConstraint, rootClass, hierarchyClass );
			addMetaDataToBuilder( methodMetaData, builders );
		}
	}


	private void addProgrammaticConfiguredMethodCascade(List<MethodCascadeDef> methodCascades, Set<AggregatedMethodMetaData.Builder> builders) {
		for ( MethodCascadeDef cascadeDef : methodCascades ) {
			MethodMetaData methodMetaData = createMethodMetaData( cascadeDef );
			addMetaDataToBuilder( methodMetaData, builders );
		}
	}

	private MethodMetaData createMethodMetaData(MethodCascadeDef cascadeDef) {
		Method method = cascadeDef.getMethod();
		List<ParameterMetaData> parameterMetaDatas = newArrayList();
		List<ParameterMetaConstraint<? extends Annotation>> parameterConstraints = Collections.emptyList();
		List<BeanMetaConstraint<? extends Annotation>> returnConstraints = Collections.emptyList();

		int i = 0;
		for ( Class<?> parameterType : method.getParameterTypes() ) {
			String parameterName = DEFAULT_PARAMETER_NAME_PREFIX + i;
			boolean isCascading = PARAMETER.equals( cascadeDef.getElementType() ) && cascadeDef.getIndex() == i;

			parameterMetaDatas.add(
					new ParameterMetaData(
							i,
							parameterType,
							parameterName,
							parameterConstraints,
							isCascading
					)
			);

			i++;
		}

		boolean isCascading = METHOD.equals( cascadeDef.getElementType() );
		return new MethodMetaData( method, parameterMetaDatas, returnConstraints, isCascading );
	}

	private <A extends Annotation> MethodMetaData createMethodMetaData(ConfiguredConstraint<A, MethodParameterConstraintLocation> constraintDef, Class<?> rootClass, Class<?> hierarchyClass) {

		Method method = constraintDef.getLocation().getMethod();
		List<ParameterMetaData> parameterMetaDatas = newArrayList();
		List<BeanMetaConstraint<? extends Annotation>> returnConstraints = Collections.emptyList();

		String parameterName = DEFAULT_PARAMETER_NAME_PREFIX + constraintDef.getLocation().getParameterIndex();

		A annotation = createAnnotationProxy( constraintDef );
		ConstraintOrigin definedIn = definedIn( rootClass, hierarchyClass );
		ConstraintDescriptorImpl<A> constraintDescriptor = new ConstraintDescriptorImpl<A>(
				annotation, constraintHelper, ElementType.PARAMETER, definedIn
		);

		ParameterMetaConstraint<A> parameterConstraint = new ParameterMetaConstraint<A>(
				constraintDescriptor, method, constraintDef.getLocation().getParameterIndex()
		);

		parameterMetaDatas.add(
				new ParameterMetaData(
						constraintDef.getLocation().getParameterIndex(),
						method.getParameterTypes()[constraintDef.getLocation().getParameterIndex()],
						parameterName,
						Arrays.<ParameterMetaConstraint<?>>asList( parameterConstraint ),
						false
				)
		);

		return new MethodMetaData( method, parameterMetaDatas, returnConstraints, false );
	}

	private void addMetaDataToBuilder(MethodMetaData methodMetaData, Set<AggregatedMethodMetaData.Builder> builders) {
		for ( AggregatedMethodMetaData.Builder OneBuilder : builders ) {
			if ( OneBuilder.accepts( methodMetaData ) ) {
				OneBuilder.addMetaData( methodMetaData );
				return;
			}
		}
		AggregatedMethodMetaData.Builder builder = new AggregatedMethodMetaData.Builder( methodMetaData );
		builders.add( builder );
	}

	/**
	 * @param rootClass The root class. That is the class for which we currently create a  {@code BeanMetaData}
	 * @param hierarchyClass The class on which the current constraint is defined on
	 *
	 * @return Returns {@code ConstraintOrigin.DEFINED_LOCALLY} if the constraint was defined on the root bean,
	 *         {@code ConstraintOrigin.DEFINED_IN_HIERARCHY} otherwise.
	 */
	private ConstraintOrigin definedIn(Class<?> rootClass, Class<?> hierarchyClass) {
		if ( hierarchyClass.equals( rootClass ) ) {
			return ConstraintOrigin.DEFINED_LOCALLY;
		}
		else {
			return ConstraintOrigin.DEFINED_IN_HIERARCHY;
		}
	}

	private <A extends Annotation> A createAnnotationProxy(ConfiguredConstraint<A, ?> config) {
		Class<A> constraintType = config.getConstraintType();
		AnnotationDescriptor<A> annotationDescriptor = new AnnotationDescriptor<A>( constraintType );
		for ( Map.Entry<String, Object> parameter : config.getParameters().entrySet() ) {
			annotationDescriptor.setValue( parameter.getKey(), parameter.getValue() );
		}

		A annotation;
		try {
			annotation = AnnotationFactory.create( annotationDescriptor );
		}
		catch ( RuntimeException e ) {
			throw new ValidationException(
					"Unable to create annotation for configured constraint: " + e.getMessage(), e
			);
		}
		return annotation;
	}

	private boolean checkPropertiesForFailFast(ConfigurationState configurationState, boolean programmaticConfiguredFailFast) {
		boolean failFast = programmaticConfiguredFailFast;
		String failFastPropValue = configurationState.getProperties().get( HibernateValidatorConfiguration.FAIL_FAST );
		if ( failFastPropValue != null ) {
			boolean tmpFailFast = Boolean.valueOf( failFastPropValue );
			if ( programmaticConfiguredFailFast && !tmpFailFast ) {
				throw new ValidationException(
						"Inconsistent fail fast configuration. Fail fast enabled via programmatic API, " +
								"but explicitly disabled via properties"
				);
			}
			failFast = tmpFailFast;
		}
		return failFast;
	}
}
