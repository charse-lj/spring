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

import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;

import java.lang.annotation.Annotation;
import java.util.*;

 /**
 * Provides {@link AnnotationTypeMapping} information for a single source
 * annotation type. Performs a recursive breadth first crawl of all
 * meta-annotations to ultimately provide a quick way to map the attributes of
 * root {@link Annotation}.
 *
 * <p>Supports convention based merging of meta-annotations as well as implicit
 * and explicit {@link AliasFor @AliasFor} aliases. Also provides information
 * about mirrored attributes.
 *
 * <p>This class is designed to be cached so that meta-annotations only need to
 * be searched once, regardless of how many times they are actually used.
  *
  * 假如现在有一个注解 @A，上面还有一个元注解 @B，@B上又存在一个元注解 @C则解析流程如下：
  *
  * 解析注解 @A，由于其已经是根注解了，故此时数据源为 null ，将数据源与他的元注解 @A 封装为一个AnnotationTypeMapping，这里称为 M1。则 M1 即为元注解 @A 与数据源的映射；
  * 解析上一步得到的数据源，也就是M1，然后获其中元注解 @A 上的元注解 @B，然后将数据源 M1 与 @B 再封装为一个AnnotationTypeMapping，这里称为 M2。则 M2 即为元注解 @B 与 M1 ——或者说 @A ——的映射；
 *
 * @author Phillip Webb
 * @see AnnotationTypeMapping
 * @since 5.2
 */
final class AnnotationTypeMappings {

	private static final IntrospectionFailureLogger failureLogger = IntrospectionFailureLogger.DEBUG;

	private static final Map<AnnotationFilter, Cache> standardRepeatablesCache = new ConcurrentReferenceHashMap<>();

	private static final Map<AnnotationFilter, Cache> noRepeatablesCache = new ConcurrentReferenceHashMap<>();


	private final RepeatableContainers repeatableContainers;

	private final AnnotationFilter filter;

	private final List<AnnotationTypeMapping> mappings;


	private AnnotationTypeMappings(RepeatableContainers repeatableContainers,
								   AnnotationFilter filter, Class<? extends Annotation> annotationType) {

		// 可重复注解的容器
		this.repeatableContainers = repeatableContainers;
		// 过滤器
		this.filter = filter;
		// 映射关系
		this.mappings = new ArrayList<>();
		// 解析当前类以及其元注解的层次结构中涉及到的全部映射关系
		addAllMappings(annotationType);
		// 映射关系解析完后对别名
		this.mappings.forEach(AnnotationTypeMapping::afterAllMappingsSet);
	}


	/**
	 * 用于将元注解的类型跟声明元注解的数据源进行绑定
	 * @param annotationType
	 */
	private void addAllMappings(Class<? extends Annotation> annotationType) {
		// 广度优先遍历注解和元注解
		Deque<AnnotationTypeMapping> queue = new ArrayDeque<>();
		// 1.1 添加待解析的元注解
		addIfPossible(queue, null, annotationType, null);
		while (!queue.isEmpty()) {
			AnnotationTypeMapping mapping = queue.removeFirst();
			this.mappings.add(mapping);
			// 继续解析下一层
			addMetaAnnotationsToQueue(queue, mapping);
		}
	}

	 /**
	  * 1.2 解析的元注解
	  * @param queue 队列.
	  * @param source 对象.
	  */
	private void addMetaAnnotationsToQueue(Deque<AnnotationTypeMapping> queue, AnnotationTypeMapping source) {
		// 获取当前注解类上直接声明的元注解
		Annotation[] metaAnnotations =
				AnnotationsScanner.getDeclaredAnnotations(source.getAnnotationType(), false);
		for (Annotation metaAnnotation : metaAnnotations) {
			// 若已经解析过了则跳过，避免“循环引用”
			if (!isMappable(source, metaAnnotation)) {
				continue;
			}
			// a.若当前正在解析的注解是容器注解，则将内部的可重复注解取出解析
			Annotation[] repeatedAnnotations = this.repeatableContainers
					.findRepeatedAnnotations(metaAnnotation);
			if (repeatedAnnotations != null) {
				for (Annotation repeatedAnnotation : repeatedAnnotations) {
					// 1.2.1 若已经解析过了则跳过，避免“循环引用”
					if (!isMappable(source, metaAnnotation)) {
						continue;
					}
					addIfPossible(queue, source, repeatedAnnotation);
				}
			} else {
				// b.若当前正在解析的注解不是容器注解，则将直接解析
				addIfPossible(queue, source, metaAnnotation);
			}
		}
	}

	private void addIfPossible(Deque<AnnotationTypeMapping> queue,
							   AnnotationTypeMapping source, Annotation ann) {

		addIfPossible(queue, source, ann.annotationType(), ann);
	}

	/**
	 *
	 * @param queue  队列,先进先出
	 * @param source 上一层.
	 * @param annotationType 待检查的注解元素类.
	 * @param ann
	 */
	private void addIfPossible(Deque<AnnotationTypeMapping> queue, @Nullable AnnotationTypeMapping source,
							   Class<? extends Annotation> annotationType, @Nullable Annotation ann) {

		try {
			// 将数据源、元注解类型和元注解实例封装为一个AnnotationTypeMapping，作为下一次处理的数据源
			queue.addLast(new AnnotationTypeMapping(source, annotationType, ann));
		} catch (Exception ex) {
			AnnotationUtils.rethrowAnnotationConfigurationException(ex);
			if (failureLogger.isEnabled()) {
				failureLogger.log("Failed to introspect meta-annotation " + annotationType.getName(),
						(source != null ? source.getAnnotationType() : null), ex);
			}
		}
	}

 	/**
	 * @param source         源信息.
	 * @param metaAnnotation 源信息上的某个注解对象.
	 * @return .
	 *
	 */
	private boolean isMappable(AnnotationTypeMapping source, @Nullable Annotation metaAnnotation) {
		return (metaAnnotation != null &&
				//注解过滤器.
				!this.filter.matches(metaAnnotation) &&
				//注解类过滤器.
				!AnnotationFilter.PLAIN.matches(source.getAnnotationType()) &&
				//循环引用
				!isAlreadyMapped(source, metaAnnotation));
	}

	/**
	 * 防止注解的循环引用. @LoopA 应用@LoopB，@LoopB引用@LoopA
	 *
	 * @param source         .
	 * @param metaAnnotation .
	 * @return .
	 */
	private boolean isAlreadyMapped(AnnotationTypeMapping source, Annotation metaAnnotation) {
		Class<? extends Annotation> annotationType = metaAnnotation.annotationType();
		AnnotationTypeMapping mapping = source;
		while (mapping != null) {
			if (mapping.getAnnotationType() == annotationType) {
				return true;
			}
			//上一层.
			mapping = mapping.getSource();
		}
		return false;
	}

	/**
	 * Get the total number of contained mappings.
	 *
	 * @return the total number of mappings
	 */
	int size() {
		return this.mappings.size();
	}

	/**
	 * Get an individual mapping from this instance.
	 * <p>Index {@code 0} will always return the root mapping; higher indexes
	 * will return meta-annotation mappings.
	 *
	 * @param index the index to return
	 * @return the {@link AnnotationTypeMapping}
	 * @throws IndexOutOfBoundsException if the index is out of range
	 *                                   (<tt>index &lt; 0 || index &gt;= size()</tt>)
	 */
	AnnotationTypeMapping get(int index) {
		return this.mappings.get(index);
	}


	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * @param annotationType the source annotation type
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType) {
		return forAnnotationType(annotationType, AnnotationFilter.PLAIN);
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * @param annotationType   the source annotation type
	 * @param annotationFilter the annotation filter used to limit which
	 *                         annotations are considered
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(
			Class<? extends Annotation> annotationType, AnnotationFilter annotationFilter) {

		return forAnnotationType(annotationType,
				RepeatableContainers.standardRepeatables(), annotationFilter);
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * @param annotationType   the source annotation type  待检查的注解元素类.
	 * @param annotationFilter the annotation filter used to limit which
	 *                         annotations are considered
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(
			Class<? extends Annotation> annotationType,
			RepeatableContainers repeatableContainers,
			AnnotationFilter annotationFilter) {
        // 针对可重复注解的容器缓存
		if (repeatableContainers == RepeatableContainers.standardRepeatables()) {
			return standardRepeatablesCache.computeIfAbsent(annotationFilter,
					key -> new Cache(repeatableContainers, key)).get(annotationType);
		}// 针对不可重复注解的容器缓存
		if (repeatableContainers == RepeatableContainers.none()) {
			return noRepeatablesCache.computeIfAbsent(annotationFilter,
					key -> new Cache(repeatableContainers, key)).get(annotationType);
		}
		// 创建一个AnnotationTypeMappings实例
		return new AnnotationTypeMappings(repeatableContainers, annotationFilter,
				annotationType);
	}

	static void clearCache() {
		standardRepeatablesCache.clear();
		noRepeatablesCache.clear();
	}


	/**
	 * Cache created per {@link AnnotationFilter}.
	 */
	private static class Cache {

		private final RepeatableContainers repeatableContainers;

		private final AnnotationFilter filter;

		private final Map<Class<? extends Annotation>, AnnotationTypeMappings> mappings;

		/**
		 * Create a cache instance with the specified filter.
		 *
		 * @param filter the annotation filter
		 */
		Cache(RepeatableContainers repeatableContainers, AnnotationFilter filter) {
			this.repeatableContainers = repeatableContainers;
			this.filter = filter;
			this.mappings = new ConcurrentReferenceHashMap<>();
		}

		/**
		 * Get or create {@link AnnotationTypeMappings} for the specified annotation type.
		 *
		 * @param annotationType the annotation type
		 * @return a new or existing {@link AnnotationTypeMappings} instance
		 */
		AnnotationTypeMappings get(Class<? extends Annotation> annotationType) {
			return this.mappings.computeIfAbsent(annotationType, this::createMappings);
		}

		AnnotationTypeMappings createMappings(Class<? extends Annotation> annotationType) {
			return new AnnotationTypeMappings(this.repeatableContainers, this.filter, annotationType);
		}
	}

}
