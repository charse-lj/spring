/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core.annotation;

import org.springframework.core.annotation.AnnotationTypeMapping.MirrorSets.MirrorSet;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Provides mapping information for a single annotation (or meta-annotation) in
 * the context of a root annotation type.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @see AnnotationTypeMappings
 * @since 5.2
 */
final class AnnotationTypeMapping {

	private static final MirrorSet[] EMPTY_MIRROR_SETS = new MirrorSet[0];


	/**
	 * this的parent
	 */
	@Nullable
	private final AnnotationTypeMapping source;

	/**
	 * this的root
	 */
	private final AnnotationTypeMapping root;

	/**
	 * 距离root的深度,root为0
	 */
	private final int distance;

	/**
	 * this所属注解类
	 */
	private final Class<? extends Annotation> annotationType;

	/**
	 * 遍历到this的注解路径，所有的注解对象类型
	 */
	private final List<Class<? extends Annotation>> metaTypes;

	/**
	 * 该注解类型实例
	 */
	@Nullable
	private final Annotation annotation;

	/**
	 * 注解属性方法列表
	 */
	private final AttributeMethods attributes;

	/**
	 *MirrorSet集合
	 *本注解里声明的属性，最终为同一个属性的别名的属性集合,为一个MirrorSet
	 */
	private final MirrorSets mirrorSets;

	/**
	 * this中每个属性在root中对应的同名的属性方法的索引
	 */
	private final int[] aliasMappings;

	/**
	 * 方便访问属性 的映射消息，如果在root中有别名，则优先获取
	 */
	private final int[] conventionMappings;

	/**
	 * 与annotationValueSource是相匹配的，定义每个属性最终从哪个注解的哪个属性获取值
	 */
	private final int[] annotationValueMappings;

	private final AnnotationTypeMapping[] annotationValueSource;

	/**
	 * @interface A{
	 *     @aliasFor(value="mz",annotation=B.class)
	 *     String name();
	 * }
	 *
	 * @interface B{
	 *     String mz();
	 * }
	 *
	 * aliasedBy --> B.mz()::[A.name()]
	 */
	private final Map<Method, List<Method>> aliasedBy;

	private final boolean synthesizable;

	/**
	 * 该注解属性方法+声明的属性方法的别名集合
	 */
	private final Set<Method> claimedAliases = new HashSet<>();


	AnnotationTypeMapping(@Nullable AnnotationTypeMapping source,
						  Class<? extends Annotation> annotationType, @Nullable Annotation annotation) {

		//上一层.
		this.source = source;
		//上一次为空,this就是根,否则从上一层获取根
		this.root = (source != null ? source.getRoot() : this);
		//深度.root深度为0
		this.distance = (source == null ? 0 : source.getDistance() + 1);
		//源注解类
		this.annotationType = annotationType;
		//从root到this的所有注解
		this.metaTypes = merge(
				source != null ? source.getMetaTypes() : null,
				annotationType);
		this.annotation = annotation;
		//注解类的所有属性方法
		this.attributes = AttributeMethods.forAnnotationType(annotationType);
		this.mirrorSets = new MirrorSets();
		this.aliasMappings = filledIntArray(this.attributes.size());
		this.conventionMappings = filledIntArray(this.attributes.size());
		this.annotationValueMappings = filledIntArray(this.attributes.size());
		this.annotationValueSource = new AnnotationTypeMapping[this.attributes.size()];
		this.aliasedBy = resolveAliasedForTargets();
		processAliases();
		addConventionMappings();
		addConventionAnnotationValues();
		this.synthesizable = computeSynthesizableFlag();
	}


	private static <T> List<T> merge(@Nullable List<T> existing, T element) {
		if (existing == null) {
			return Collections.singletonList(element);
		}
		List<T> merged = new ArrayList<>(existing.size() + 1);
		merged.addAll(existing);
		merged.add(element);
		return Collections.unmodifiableList(merged);
	}

	/**
	 * 返回每个属性方法的所有别名（本注解声明的属性方法）.
	 * @return .
	 */
	private Map<Method, List<Method>> resolveAliasedForTargets() {
		Map<Method, List<Method>> aliasedBy = new HashMap<>();
		//遍历注解属性方法
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			//注解属性方法上的@AliasFor注解对象
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			if (aliasFor != null) {
				//获取被@aliasFor标注的注解方法-> @aliasFor(value="xx",annotation=B.class)->在B注解对象中寻找xx()方法
				Method target = resolveAliasTarget(attribute, aliasFor);
				aliasedBy.computeIfAbsent(target, key -> new ArrayList<>()).add(attribute);
			}
		}
		return Collections.unmodifiableMap(aliasedBy);
	}

	/**
	 *
	 * @param attribute  含有@AliasFor注解的方法.
	 * @param aliasFor 注解方法上@AliasFor注解对象.
	 * @return .
	 */
	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor) {
		return resolveAliasTarget(attribute, aliasFor, true);
	}

	/**
	 * @param attribute      含有@AliasFor注解的方法.
	 * @param aliasFor       注解方法上@AliasFor注解对象.
	 * @param checkAliasPair .
	 * @return
	 * 获取对应注解的注解属性方法
	 * @AliasFor(value="test",annotation="A.class") ->找到注解A中的属性test
	 */
	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor, boolean checkAliasPair) {
		//@AliasFor注解中，包含value和attribute,这两个属性又被@AliasFor修饰,互为别名,在使用时,只能存在其一.
		if (StringUtils.hasText(aliasFor.value()) && StringUtils.hasText(aliasFor.attribute())) {
			throw new AnnotationConfigurationException(String.format(
					"In @AliasFor declared on %s, attribute 'attribute' and its alias 'value' " +
							"are present with values of '%s' and '%s', but only one is permitted.",
					AttributeMethods.describe(attribute), aliasFor.attribute(),
					aliasFor.value()));
		}
		//目标注解
		Class<? extends Annotation> targetAnnotation = aliasFor.annotation();
		//默认值
		if (targetAnnotation == Annotation.class) {
			targetAnnotation = this.annotationType;
		}
		//attribute属性值
		String targetAttributeName = aliasFor.attribute();
		// attribute属性值没有值
		if (!StringUtils.hasLength(targetAttributeName)) {
			//获取 value属性值
			targetAttributeName = aliasFor.value();
		}
		//value属性也没有值.
		if (!StringUtils.hasLength(targetAttributeName)) {
			//获取方法的名称
			targetAttributeName = attribute.getName();
		}
		//获取目标注解类中的所有方法,并获取特定名称的方法
		Method target = AttributeMethods.forAnnotationType(targetAnnotation).get(targetAttributeName);
		if (target == null) {
			if (targetAnnotation == this.annotationType) {
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for '%s' which is not present.",
						AttributeMethods.describe(attribute), targetAttributeName));
			}
			throw new AnnotationConfigurationException(String.format(
					"%s is declared as an @AliasFor nonexistent %s.",
					StringUtils.capitalize(AttributeMethods.describe(attribute)),
					AttributeMethods.describe(targetAnnotation, targetAttributeName)));
		}
		//方法相同
		if (target.equals(attribute)) {
			throw new AnnotationConfigurationException(String.format(
					"@AliasFor declaration on %s points to itself. " +
							"Specify 'annotation' to point to a same-named attribute on a meta-annotation.",
					AttributeMethods.describe(attribute)));
		}
		if (!isCompatibleReturnType(attribute.getReturnType(), target.getReturnType())) {
			throw new AnnotationConfigurationException(String.format(
					"Misconfigured aliases: %s and %s must declare the same return type.",
					AttributeMethods.describe(attribute),
					AttributeMethods.describe(target)));
		}
		//要互为alias,否则报错
		if (isAliasPair(target) && checkAliasPair) {
			AliasFor targetAliasFor = target.getAnnotation(AliasFor.class);
			if (targetAliasFor != null) {
				Method mirror = resolveAliasTarget(target, targetAliasFor, false);
				if (!mirror.equals(attribute)) {
					throw new AnnotationConfigurationException(String.format(
							"%s must be declared as an @AliasFor %s, not %s.",
							StringUtils.capitalize(AttributeMethods.describe(target)),
							AttributeMethods.describe(attribute), AttributeMethods.describe(mirror)));
				}
			}
		}
		return target;
	}

	private boolean isAliasPair(Method target) {
		//源注解类和目标注解类相同.
		return (this.annotationType == target.getDeclaringClass());
	}

	private boolean isCompatibleReturnType(Class<?> attributeType, Class<?> targetType) {
		return (attributeType == targetType || attributeType == targetType.getComponentType());
	}

	/**
	 * 处理别名，生成MirrorSets
	 * 对该注解中的每个注解方法处理别名.
	 */
	private void processAliases() {
		List<Method> aliases = new ArrayList<>();
		for (int i = 0; i < this.attributes.size(); i++) {
			aliases.clear();
			//该注解的注解属性方法
			aliases.add(this.attributes.get(i));
			//收集注解属性别名
			collectAliases(aliases);
			if (aliases.size() > 1) {
				//>1表示该注解属性方法代表的属性有注解属性别名.
				processAliases(i, aliases);
			}
		}
	}

	/**
	 * @param aliases 递归所有层,查找该层的注解方法别名方法.
	 */
	private void collectAliases(List<Method> aliases) {
		AnnotationTypeMapping mapping = this;
		while (mapping != null) {
			int size = aliases.size();
			for (int j = 0; j < size; j++) {
				List<Method> additional = mapping.aliasedBy.get(aliases.get(j));
				if (additional != null) {
					aliases.addAll(additional);
				}
			}
			mapping = mapping.source;
		}
	}

	/**
	 * 对每个属性方法，处理它的别名
	 * @param attributeIndex .
	 * @param aliases this.attributes[attributeIndex]()+该属性方法的所有层级的别名属性方法,地位等同.
	 */
	private void processAliases(int attributeIndex, List<Method> aliases) {
		//获取root声明的第一个别名属性的index。-1表示root不存dd在此属性方法的别名
		//如果>-1,表示root中索引位置的属性是该aliases的别名，地位等同
		int rootAttributeIndex = getFirstRootAttributeIndex(aliases);
		AnnotationTypeMapping mapping = this;
		while (mapping != null) {
			//在root中有别名，并且此mapping不是root
			if (rootAttributeIndex != -1 && mapping != this.root) {
				for (int i = 0; i < mapping.attributes.size(); i++) {
					//如果别名中有mapping中属性方法，则对应的属性index值为root的属性的index。
					if (aliases.contains(mapping.attributes.get(i))) {
						mapping.aliasMappings[i] = rootAttributeIndex;
					}
				}
			}
			//更新mapping的mirrorSets
			mapping.mirrorSets.updateFrom(aliases);
			//mapping声明的属性方法的别名集合。
			mapping.claimedAliases.addAll(aliases);
			if (mapping.annotation != null) {
				//返回本mapping每个属性最终取值的属性方法的序号 数组。
				int[] resolvedMirrors = mapping.mirrorSets.resolve(null,
						mapping.annotation, ReflectionUtils::invokeMethod);
				for (int i = 0; i < mapping.attributes.size(); i++) {
					//本属性方法是别名，则设置注解值的最终来源（mapping和属性序号）
					if (aliases.contains(mapping.attributes.get(i))) {
						this.annotationValueMappings[attributeIndex] = resolvedMirrors[i];
						this.annotationValueSource[attributeIndex] = mapping;
					}
				}
			}
			mapping = mapping.source;
		}
	}

	/**
	 * @param aliases 原注解属性方法+所有别名注解属性方法
	 * @return
	 */
	private int getFirstRootAttributeIndex(Collection<Method> aliases) {
		AttributeMethods rootAttributes = this.root.getAttributes();
		for (int i = 0; i < rootAttributes.size(); i++) {
			//aliases中包含根注解属性方法的索引
			if (aliases.contains(rootAttributes.get(i))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 *生成从root访问属性的方便属性方法信息
	 */
	private void addConventionMappings() {
		if (this.distance == 0) {
			return;
		}
		AttributeMethods rootAttributes = this.root.getAttributes();
		//此时，元素值全为-1
		int[] mappings = this.conventionMappings;
		for (int i = 0; i < mappings.length; i++) {
			String name = this.attributes.get(i).getName();
			MirrorSet mirrors = getMirrorSets().getAssigned(i);
			//root中是否存在同名的属性
			int mapped = rootAttributes.indexOf(name);
			//root中存在同名的属性，并且属性名不为value
			if (!MergedAnnotation.VALUE.equals(name) && mapped != -1) {
				mappings[i] = mapped;
				if (mirrors != null) {
					for (int j = 0; j < mirrors.size(); j++) {
						//同一属性的所有别名，设置成一样的root 属性index
						mappings[mirrors.getAttributeIndex(j)] = mapped;
					}
				}
			}
		}
	}

	/**
	 * 更新各级AnnotationTypeMapping的annotationValueMappings和annotationValueSource.
	 */
	private void addConventionAnnotationValues() {
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			boolean isValueAttribute = MergedAnnotation.VALUE.equals(attribute.getName());
			AnnotationTypeMapping mapping = this;
			//在向root端（mapping.distance 比自己下的）遍历
			while (mapping != null && mapping.distance > 0) {
				int mapped = mapping.getAttributes().indexOf(attribute.getName());
				if (mapped != -1 && isBetterConventionAnnotationValue(i, isValueAttribute, mapping)) {
					this.annotationValueMappings[i] = mapped;
					this.annotationValueSource[i] = mapping;
				}
				mapping = mapping.source;
			}
		}
	}

	/**
	 * 是更好的注解值获取属性方法（Value属性优先，distance较小的优先）.
	 * @param index
	 * @param isValueAttribute
	 * @param mapping
	 * @return
	 */
	private boolean isBetterConventionAnnotationValue(int index, boolean isValueAttribute,
													  AnnotationTypeMapping mapping) {

		//原来没有获取值的属性方法。
		if (this.annotationValueMappings[index] == -1) {
			return true;
		}
		int existingDistance = this.annotationValueSource[index].distance;
		return !isValueAttribute && existingDistance > mapping.distance;
	}

	@SuppressWarnings("unchecked")
	private boolean computeSynthesizableFlag() {
		// Uses @AliasFor for local aliases?
		for (int index : this.aliasMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Uses @AliasFor for attribute overrides in meta-annotations?
		if (!this.aliasedBy.isEmpty()) {
			return true;
		}

		// Uses convention-based attribute overrides in meta-annotations?
		for (int index : this.conventionMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Has nested annotations or arrays of annotations that are synthesizable?
		if (getAttributes().hasNestedAnnotation()) {
			AttributeMethods attributeMethods = getAttributes();
			for (int i = 0; i < attributeMethods.size(); i++) {
				Method method = attributeMethods.get(i);
				Class<?> type = method.getReturnType();
				if (type.isAnnotation() || (type.isArray() && type.getComponentType().isAnnotation())) {
					Class<? extends Annotation> annotationType =
							(Class<? extends Annotation>) (type.isAnnotation() ? type : type.getComponentType());
					AnnotationTypeMapping mapping = AnnotationTypeMappings.forAnnotationType(annotationType).get(0);
					if (mapping.isSynthesizable()) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/**
	 * Method called after all mappings have been set. At this point no further
	 * lookups from child mappings will occur.
	 */
	void afterAllMappingsSet() {
		validateAllAliasesClaimed();
		for (int i = 0; i < this.mirrorSets.size(); i++) {
			validateMirrorSet(this.mirrorSets.get(i));
		}
		this.claimedAliases.clear();
	}

	private void validateAllAliasesClaimed() {
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			if (aliasFor != null && !this.claimedAliases.contains(attribute)) {
				Method target = resolveAliasTarget(attribute, aliasFor);
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for %s which is not meta-present.",
						AttributeMethods.describe(attribute), AttributeMethods.describe(target)));
			}
		}
	}

	private void validateMirrorSet(MirrorSet mirrorSet) {
		Method firstAttribute = mirrorSet.get(0);
		Object firstDefaultValue = firstAttribute.getDefaultValue();
		for (int i = 1; i <= mirrorSet.size() - 1; i++) {
			Method mirrorAttribute = mirrorSet.get(i);
			Object mirrorDefaultValue = mirrorAttribute.getDefaultValue();
			if (firstDefaultValue == null || mirrorDefaultValue == null) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare default values.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
			if (!ObjectUtils.nullSafeEquals(firstDefaultValue, mirrorDefaultValue)) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare the same default value.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
		}
	}

	/**
	 * Get the root mapping.
	 *
	 * @return the root mapping
	 */
	AnnotationTypeMapping getRoot() {
		return this.root;
	}

	/**
	 * Get the source of the mapping or {@code null}.
	 *
	 * @return the source of the mapping
	 */
	@Nullable
	AnnotationTypeMapping getSource() {
		return this.source;
	}

	/**
	 * Get the distance of this mapping.
	 *
	 * @return the distance of the mapping
	 */
	int getDistance() {
		return this.distance;
	}

	/**
	 * Get the type of the mapped annotation.
	 *
	 * @return the annotation type
	 */
	Class<? extends Annotation> getAnnotationType() {
		return this.annotationType;
	}

	List<Class<? extends Annotation>> getMetaTypes() {
		return this.metaTypes;
	}

	/**
	 * Get the source annotation for this mapping. This will be the
	 * meta-annotation, or {@code null} if this is the root mapping.
	 *
	 * @return the source annotation of the mapping
	 */
	@Nullable
	Annotation getAnnotation() {
		return this.annotation;
	}

	/**
	 * Get the annotation attributes for the mapping annotation type.
	 *
	 * @return the attribute methods
	 */
	AttributeMethods getAttributes() {
		return this.attributes;
	}

	/**
	 * Get the related index of an alias mapped attribute, or {@code -1} if
	 * there is no mapping. The resulting value is the index of the attribute on
	 * the root annotation that can be invoked in order to obtain the actual
	 * value.
	 *
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getAliasMapping(int attributeIndex) {
		return this.aliasMappings[attributeIndex];
	}

	/**
	 * Get the related index of a convention mapped attribute, or {@code -1}
	 * if there is no mapping. The resulting value is the index of the attribute
	 * on the root annotation that can be invoked in order to obtain the actual
	 * value.
	 *
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getConventionMapping(int attributeIndex) {
		return this.conventionMappings[attributeIndex];
	}

	/**
	 * Get a mapped attribute value from the most suitable
	 * {@link #getAnnotation() meta-annotation}.
	 * <p>The resulting value is obtained from the closest meta-annotation,
	 * taking into consideration both convention and alias based mapping rules.
	 * For root mappings, this method will always return {@code null}.
	 *
	 * @param attributeIndex      the attribute index of the source attribute
	 * @param metaAnnotationsOnly if only meta annotations should be considered.
	 *                            If this parameter is {@code false} then aliases within the annotation will
	 *                            also be considered.
	 * @return the mapped annotation value, or {@code null}
	 */
	@Nullable
	Object getMappedAnnotationValue(int attributeIndex, boolean metaAnnotationsOnly) {
		int mappedIndex = this.annotationValueMappings[attributeIndex];
		if (mappedIndex == -1) {
			return null;
		}
		AnnotationTypeMapping source = this.annotationValueSource[attributeIndex];
		if (source == this && metaAnnotationsOnly) {
			return null;
		}
		return ReflectionUtils.invokeMethod(source.attributes.get(mappedIndex), source.annotation);
	}

	/**
	 * Determine if the specified value is equivalent to the default value of the
	 * attribute at the given index.
	 *
	 * @param attributeIndex the attribute index of the source attribute
	 * @param value          the value to check
	 * @param valueExtractor the value extractor used to extract values from any
	 *                       nested annotations
	 * @return {@code true} if the value is equivalent to the default value
	 */
	boolean isEquivalentToDefaultValue(int attributeIndex, Object value, ValueExtractor valueExtractor) {

		Method attribute = this.attributes.get(attributeIndex);
		return isEquivalentToDefaultValue(attribute, value, valueExtractor);
	}

	/**
	 * Get the mirror sets for this type mapping.
	 *
	 * @return the attribute mirror sets
	 */
	MirrorSets getMirrorSets() {
		return this.mirrorSets;
	}

	/**
	 * Determine if the mapped annotation is <em>synthesizable</em>.
	 * <p>Consult the documentation for {@link MergedAnnotation#synthesize()}
	 * for an explanation of what is considered synthesizable.
	 *
	 * @return {@code true} if the mapped annotation is synthesizable
	 * @since 5.2.6
	 */
	boolean isSynthesizable() {
		return this.synthesizable;
	}


	/**
	 * @param size 注解方法长度.
	 * @return 都初始化为-1.
	 */
	private static int[] filledIntArray(int size) {
		int[] array = new int[size];
		Arrays.fill(array, -1);
		return array;
	}

	private static boolean isEquivalentToDefaultValue(Method attribute, Object value,
													  ValueExtractor valueExtractor) {

		return areEquivalent(attribute.getDefaultValue(), value, valueExtractor);
	}

	private static boolean areEquivalent(@Nullable Object value, @Nullable Object extractedValue,
										 ValueExtractor valueExtractor) {

		if (ObjectUtils.nullSafeEquals(value, extractedValue)) {
			return true;
		}
		if (value instanceof Class && extractedValue instanceof String) {
			return areEquivalent((Class<?>) value, (String) extractedValue);
		}
		if (value instanceof Class[] && extractedValue instanceof String[]) {
			return areEquivalent((Class[]) value, (String[]) extractedValue);
		}
		if (value instanceof Annotation) {
			return areEquivalent((Annotation) value, extractedValue, valueExtractor);
		}
		return false;
	}

	private static boolean areEquivalent(Class<?>[] value, String[] extractedValue) {
		if (value.length != extractedValue.length) {
			return false;
		}
		for (int i = 0; i < value.length; i++) {
			if (!areEquivalent(value[i], extractedValue[i])) {
				return false;
			}
		}
		return true;
	}

	private static boolean areEquivalent(Class<?> value, String extractedValue) {
		return value.getName().equals(extractedValue);
	}

	private static boolean areEquivalent(Annotation annotation, @Nullable Object extractedValue,
										 ValueExtractor valueExtractor) {

		AttributeMethods attributes = AttributeMethods.forAnnotationType(annotation.annotationType());
		for (int i = 0; i < attributes.size(); i++) {
			Method attribute = attributes.get(i);
			Object value1 = ReflectionUtils.invokeMethod(attribute, annotation);
			Object value2;
			if (extractedValue instanceof TypeMappedAnnotation) {
				value2 = ((TypeMappedAnnotation<?>) extractedValue).getValue(attribute.getName()).orElse(null);
			} else {
				value2 = valueExtractor.extract(attribute, extractedValue);
			}
			if (!areEquivalent(value1, value2, valueExtractor)) {
				return false;
			}
		}
		return true;
	}


	/**
	 * A collection of {@link MirrorSet} instances that provides details of all
	 * defined mirrors.
	 */
	class MirrorSets {

		private MirrorSet[] mirrorSets;

		private final MirrorSet[] assigned;

		MirrorSets() {
			this.assigned = new MirrorSet[attributes.size()];
			this.mirrorSets = EMPTY_MIRROR_SETS;
		}

		void updateFrom(Collection<Method> aliases) {
			MirrorSet mirrorSet = null;
			int size = 0;
			int last = -1;
			//mapping注解属性方法中,
			for (int i = 0; i < attributes.size(); i++) {
				Method attribute = attributes.get(i);
				if (aliases.contains(attribute)) {
					size++;
					if (size > 1) {
						if (mirrorSet == null) {
							mirrorSet = new MirrorSet();
							this.assigned[last] = mirrorSet;
						}
						this.assigned[i] = mirrorSet;
					}
					last = i;
				}
			}
			if (mirrorSet != null) {
				mirrorSet.update();
				Set<MirrorSet> unique = new LinkedHashSet<>(Arrays.asList(this.assigned));
				unique.remove(null);
				this.mirrorSets = unique.toArray(EMPTY_MIRROR_SETS);
			}
		}

		int size() {
			return this.mirrorSets.length;
		}

		MirrorSet get(int index) {
			return this.mirrorSets[index];
		}

		@Nullable
		MirrorSet getAssigned(int attributeIndex) {
			return this.assigned[attributeIndex];
		}

		int[] resolve(@Nullable Object source, @Nullable Object annotation, ValueExtractor valueExtractor) {
			int[] result = new int[attributes.size()];
			for (int i = 0; i < result.length; i++) {
				result[i] = i;
			}
			for (int i = 0; i < size(); i++) {
				MirrorSet mirrorSet = get(i);
				int resolved = mirrorSet.resolve(source, annotation, valueExtractor);
				for (int j = 0; j < mirrorSet.size; j++) {
					result[mirrorSet.indexes[j]] = resolved;
				}
			}
			return result;
		}


		/**
		 * A single set of mirror attributes.
		 */
		class MirrorSet {

			private int size;

			private final int[] indexes = new int[attributes.size()];

			void update() {
				this.size = 0;
				Arrays.fill(this.indexes, -1);
				for (int i = 0; i < MirrorSets.this.assigned.length; i++) {
					if (MirrorSets.this.assigned[i] == this) {
						this.indexes[this.size] = i;
						this.size++;
					}
				}
			}

			<A> int resolve(@Nullable Object source, @Nullable A annotation, ValueExtractor valueExtractor) {
				int result = -1;
				Object lastValue = null;
				for (int i = 0; i < this.size; i++) {
					Method attribute = attributes.get(this.indexes[i]);
					Object value = valueExtractor.extract(attribute, annotation);
					boolean isDefaultValue = (value == null ||
							isEquivalentToDefaultValue(attribute, value, valueExtractor));
					if (isDefaultValue || ObjectUtils.nullSafeEquals(lastValue, value)) {
						if (result == -1) {
							result = this.indexes[i];
						}
						continue;
					}
					if (lastValue != null && !ObjectUtils.nullSafeEquals(lastValue, value)) {
						String on = (source != null) ? " declared on " + source : "";
						throw new AnnotationConfigurationException(String.format(
								"Different @AliasFor mirror values for annotation [%s]%s; attribute '%s' " +
										"and its alias '%s' are declared with values of [%s] and [%s].",
								getAnnotationType().getName(), on,
								attributes.get(result).getName(),
								attribute.getName(),
								ObjectUtils.nullSafeToString(lastValue),
								ObjectUtils.nullSafeToString(value)));
					}
					result = this.indexes[i];
					lastValue = value;
				}
				return result;
			}

			int size() {
				return this.size;
			}

			Method get(int index) {
				int attributeIndex = this.indexes[index];
				return attributes.get(attributeIndex);
			}

			int getAttributeIndex(int index) {
				return this.indexes[index];
			}
		}
	}

}
