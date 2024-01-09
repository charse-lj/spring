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
  *
  * 根据@AlisaFor 作用与注解内和注解外，造成的效果可以简单分为两种
  * 镜像：当同一注解类中的两个属性互为别名时，则对两者任一属性赋值，等同于对另一属性赋值；
  * 覆写：当子注解和元注解中的两个属性互为别名时，对子注解中的属性赋值，将覆盖元注解中的属性；
 */
final class AnnotationTypeMapping {

	private static final MirrorSet[] EMPTY_MIRROR_SETS = new MirrorSet[0];


 	/**
	 *  声明当前元注解类型的数据源
	 */
	@Nullable
	private final AnnotationTypeMapping source;

 	/**
	 * 根节点
	 */
	private final AnnotationTypeMapping root;

 	/**
	 * 距离根节点的深度,root为0
	 */
	private final int distance;

 	/**
	 * 当前元注解类型
	 */
	private final Class<? extends Annotation> annotationType;

 	/**
	 * 到当前元注解为止前面合并了多少元注解
	 * 遍历到this的注解路径，所有的注解对象类型
	 */
	private final List<Class<? extends Annotation>> metaTypes;

 	/**
	 * 当前元注解
	 */
	@Nullable
	private final Annotation annotation;

	/**
	 * 注解属性方法列表
	 */
	private final AttributeMethods attributes;

  	/**
	 * MirrorSet集合
	 * 本注解里声明的属性，最终为同一个属性的别名的属性集合,为一个MirrorSet
	 * 镜像效果依赖于 MirrorSet -->当同一注解类中的两个属性互为别名时，则对两者任一属性赋值，等同于对另一属性赋值；
	 */
	private final MirrorSets mirrorSets;

  	/**
	 * 本注解总每个属性在root中对应的别名的属性方法的索引,没有就是-1
	 */
	private final int[] aliasMappings;

	/**
	 * 方便访问属性 的映射消息，如果在root中有别名，则优先获取
	 */
	private final int[] conventionMappings;

  	/**
	 * 与annotationValueSource是相匹配的，定义每个属性最终从哪个注解的哪个属性获取值
	 * 覆写：当子注解和元注解中的两个属性互为别名时，对子注解中的属性赋值，将覆盖元注解中的属性
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
	 *   --> B.mz()::[A.name()]
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
		//注解对象.
		this.annotation = annotation;
		//注解中的所有属性方法 -->某个属性在属性方法数组中的下标 index = 1 ，则后续所有相关数组下标为 1 的位置，都与该属性有关。
		this.attributes = AttributeMethods.forAnnotationType(annotationType);
		this.mirrorSets = new MirrorSets();
		this.aliasMappings = filledIntArray(this.attributes.size());
		this.conventionMappings = filledIntArray(this.attributes.size());
		this.annotationValueMappings = filledIntArray(this.attributes.size());
		this.annotationValueSource = new AnnotationTypeMapping[this.attributes.size()];
		//aliasedBy -->别名注解属性方法到本注解属性方法的映射,别名属性方法可能属于本注解,可能不属于
		this.aliasedBy = resolveAliasedForTargets();
		// 初始化别名属性，为所有存在别名的属性建立MirrorSet
		processAliases();
		// 为当前注解内互为并名的属性建立属性映射
		addConventionMappings();
		// 为跨注解互为别名的属性建立属性映射
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
	 * 将AttributeMethods中所有的带有@AliasFor注解的属性方法取出，然后解析注解并生成别名属性映射表
	 * @return .
	 */
	private Map<Method, List<Method>> resolveAliasedForTargets() {
		Map<Method, List<Method>> aliasedBy = new HashMap<>();
		//遍历注解属性方法
		for (int i = 0; i < this.attributes.size(); i++) {
			// attributes 底层为数组,索引遍历
			Method attribute = this.attributes.get(i);
			//获取改注解属性方法上的@AliasFor注解对象--> 包含配置的值
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			if (aliasFor != null) {
				//存在 -> AliasFor aliasFor = @aliasFor(value="xx",annotation=B.class)
				Method target = resolveAliasTarget(attribute, aliasFor);
				// 获取别名指定的注解类中的方法，建立映射集合 该方法别名属性方法 -> [该方法]
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
	 * @param aliasFor       注解方法上@AliasFor注解对象 --> 用户获取注解中配置的值.
	 * @param checkAliasPair .
	 * @return
	 * 获取对应注解的注解属性方法
	 * AliasFor aliasFor = @AliasFor(value="test",annotation="A.class") ->找到注解A中的属性test
	 * AliasFor aliasFor = @AliasFor(value="test")
	 */
	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor, boolean checkAliasPair) {
		//@AliasFor注解中，包含value和attribute属性,这两个属性又被@AliasFor修饰,互为别名,在使用时,只能存在其一.
		if (StringUtils.hasText(aliasFor.value()) && StringUtils.hasText(aliasFor.attribute())) {
			throw new AnnotationConfigurationException(String.format(
					"In @AliasFor declared on %s, attribute 'attribute' and its alias 'value' " +
							"are present with values of '%s' and '%s', but only one is permitted.",
					AttributeMethods.describe(attribute), aliasFor.attribute(),
					aliasFor.value()));
		}
		//目标注解属性方法上的注解对象AliasFor的属性值 --> annotation
		Class<? extends Annotation> targetAnnotation = aliasFor.annotation();
		//如果没配置--> 默认值为当前注解类
		if (targetAnnotation == Annotation.class) {
			targetAnnotation = this.annotationType;
		}
		//目标注解属性方法上的注解对象AliasFor的属性值 --> attribute
		String targetAttributeName = aliasFor.attribute();
		if (!StringUtils.hasLength(targetAttributeName)) {
			//为空 --> 获取 目标注解属性方法上的注解对象AliasFor的value属性值
			targetAttributeName = aliasFor.value();
		}

		//为空.
		if (!StringUtils.hasLength(targetAttributeName)) {
			//获取目标注解属性方法的方法名 --> 目标注解属性
			targetAttributeName = attribute.getName();
		}

		Method target =
				//获取目标注解类中的所有方法
				AttributeMethods.forAnnotationType(targetAnnotation)
				//获取特定名称的方法
				.get(targetAttributeName);
		if (target == null) {
			//在目标注解中定义-->AliasFor aliasFor = @AliasFor(value="test")
			if (targetAnnotation == this.annotationType) {
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for '%s' which is not present.",
						AttributeMethods.describe(attribute), targetAttributeName));
			}
			//另一个注解中定义 --> AliasFor aliasFor = @AliasFor(value="test",annotation="A.class")
			throw new AnnotationConfigurationException(String.format(
					"%s is declared as an @AliasFor nonexistent %s.",
					StringUtils.capitalize(AttributeMethods.describe(attribute)),
					AttributeMethods.describe(targetAnnotation, targetAttributeName)));
		}
		//方法相同 --> 来自同一个注解
		if (target.equals(attribute)) {
			throw new AnnotationConfigurationException(String.format(
					"@AliasFor declaration on %s points to itself. " +
							"Specify 'annotation' to point to a same-named attribute on a meta-annotation.",
					AttributeMethods.describe(attribute)));
		}
		//方法返回值不同.
		if (!isCompatibleReturnType(attribute.getReturnType(), target.getReturnType())) {
			throw new AnnotationConfigurationException(String.format(
					"Misconfigured aliases: %s and %s must declare the same return type.",
					AttributeMethods.describe(attribute),
					AttributeMethods.describe(target)));
		}
		//源注解类和目标注解方法所在的类相同 --> 要互为alias,否则报错
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
		//源注解类和目标注解方法所在的类相同
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
		//遍历本注解属性
		for (int i = 0; i < this.attributes.size(); i++) {
			// 复用集合避免重复创建
			aliases.clear();
			//本注解的注解属性方法,放第一个
			aliases.add(this.attributes.get(i));
			// 1.收集该注解属性方法的别名注解属性方法,递归收集,收集到的结果依次放入
			collectAliases(aliases);
			if (aliases.size() > 1) {
				// 2.处理该注解中第i个属性别名方法的别名集合
				processAliases(i, aliases);
			}
		}
	}

 	/**
	 * @param aliases 递归所有层,查找本注解的某一个注解方法的别名注解方法.
	 */
	private void collectAliases(List<Method> aliases) {
		//递归
		AnnotationTypeMapping mapping = this;
		while (mapping != null) {
			int size = aliases.size();
			for (int i = 0; i < size; i++) {
				//与本注解中的属性互为别名时,该方法会找到值,放入aliases
				//与子注解中的属性互为别名时,递归过程中会找到对应的
				List<Method> additional = mapping.aliasedBy.get(aliases.get(i));
				if (additional != null) {
					aliases.addAll(additional);
				}
			}
			// 继续向声明当前元注解的子注解递归
			mapping = mapping.source;
		}
	}

   	/**
	 * 对每个属性方法，处理它的别名
	 * @param attributeIndex .本注解的某一个属性方法的索引
	 * @param aliases 本注解的某一个属性方法的别名方法集合(各个层级的)
	 */
	private void processAliases(int attributeIndex, List<Method> aliases) {
		//获取root声明的第一个别名属性的index。-1表示root不存在此属性方法的别名
		//如果>-1,表示root中索引位置的属性是该aliases的别名，地位等同
		// 1.若根注解——即最小的子注解——存在以元注解属性作为别名的原始属性，则以根注解属性覆盖元注解中的属性，并在该元注解的成员变量 aliasMappings中记录根注解原始属性的下标；
		int rootAttributeIndex = getFirstRootAttributeIndex(aliases);
		//递归处理
		AnnotationTypeMapping mapping = this;
		while (mapping != null) {
			// rootAttributeIndex != -1 表示别名属性方法集合
			// 则将当前处理的注解aliasMappings与设置为根注解中对应属性的值
			// 即使用子注解的值覆盖元注解的值
			if (rootAttributeIndex != -1 && mapping != this.root) {
				//遍历注解属性方法,i为该属性方法的下表
				for (int i = 0; i < mapping.attributes.size(); i++) {
					//如果别名中有mapping中属性方法
					if (aliases.contains(mapping.attributes.get(i))) {
						//对应的属性index值设置为root的属性的index。
						mapping.aliasMappings[i] = rootAttributeIndex;
					}
				}
			}
			// 2.为各级注解中同一注解内互为别名的字段，以及根注解中不存在的、且不同注解间互为别名的字段建立镜像
			mapping.mirrorSets.updateFrom(aliases);
			//mapping声明的属性方法的别名集合。
			mapping.claimedAliases.addAll(aliases);

			// 3.根据MirrorSet，构建各级注解中被作为别名属性的属性，与调用时实际对应的注解属性及子类注解实例的映射表annotationValueMapping
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
			// 从当前元注解向子注解递归
			mapping = mapping.source;
		}
	}

 	/**
	 * @param aliases 互为别名的注解属性方法.
	 * @return 返回根的第一个注解属性方法的索引,如果存在于互为别名的注解属性方法集合中 或者-1(不存在.)
	 */
	private int getFirstRootAttributeIndex(Collection<Method> aliases) {
		//跟注解属性方法集合.
		AttributeMethods rootAttributes = this.root.getAttributes();
		for (int i = 0; i < rootAttributes.size(); i++) {
			//aliases中包含根注解属性方法
			if (aliases.contains(rootAttributes.get(i))) {
				//返回根的第一个注解属性方法的索引,如果存在于互为别名的注解属性方法集合中
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

		//同层级中互为属性别名方法会被赋值同一个MirrorSet,数组下表代表该注解中对应属性索引
		private final MirrorSet[] assigned;

		MirrorSets() {
			this.assigned = new MirrorSet[attributes.size()];
			this.mirrorSets = EMPTY_MIRROR_SETS;
		}

  		/**
		 *
		 * @param aliases 本注解的某一个属性方法的别名方法集合（有可能是同层级的,有可能是不同层级的）
		 */
		void updateFrom(Collection<Method> aliases) {
			MirrorSet mirrorSet = null;
			int size = 0;
			int last = -1;
			//镜像效果依赖于 MirrorSet -->当同一注解类中的两个属性互为别名时，则对两者任一属性赋值，等同于对另一属性赋值
			//遍历本注解属性方法
			for (int i = 0; i < attributes.size(); i++) {
				//本注解属性方法
				Method attribute = attributes.get(i);
				//存在于别名属性方法集合中 --> 当前属性方法和 别名集合互为别名.
				if (aliases.contains(attribute)) {
					//个数++
					size++;
					//第一个满足的忽略,此时互为别名,总有一个是其注解属性方法,且为其自身
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
			//说明本注解中有多个属性方法互为别名
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
				//同层级中互为属性别名方法的属性和下标
				MirrorSet mirrorSet = get(i);
				//获取本注解中互为别名的属性方法下标 --> 有值,取最后一个,没有值,取第一个默认值
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

			//同层级中互为属性别名方法的个数
			private int size;

			//同层级中互为属性别名方法的下标
			private final int[] indexes = new int[attributes.size()];

			void update() {
				this.size = 0;
				Arrays.fill(this.indexes, -1);
				for (int i = 0; i < MirrorSets.this.assigned.length; i++) {
					//i索引处,本注解中有别名属性方法
					if (MirrorSets.this.assigned[i] == this) {
						//设置同层级中互为属性别名方法的下标
						this.indexes[this.size] = i;
						this.size++;
					}
				}
			}

			<A> int resolve(@Nullable Object source, @Nullable A annotation, ValueExtractor valueExtractor) {
				int result = -1;
				Object lastValue = null;
				for (int i = 0; i < this.size; i++) {
					//获取本注解中互为别名的属性方法
					Method attribute = attributes.get(this.indexes[i]);
					Object value = valueExtractor.extract(attribute, annotation);
					boolean isDefaultValue = (value == null ||
							isEquivalentToDefaultValue(attribute, value, valueExtractor));
					//默认值
					if (isDefaultValue || ObjectUtils.nullSafeEquals(lastValue, value)) {
						if (result == -1) {
							//取第一个是默认值的下标
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
					//属性不是默认值,取该属性下标
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
