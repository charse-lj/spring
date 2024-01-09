/*
 * Copyright 2002-2019 the original author or authors.
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

import java.lang.annotation.Annotation;

 /**
 * Strategy interface used to select between two {@link MergedAnnotation}
 * instances.
  *
  * 本质上就是一个比较器，用于从两个注解中选择出一个权重更高的注解，此处的“权重”实际就是指注解离被查找元素的距离，距离越近权重就越高
  *
  * 假如现在有个被查找元素 Foo.class，他上面有一个注解@A，@A上还有一个元注解 @B，此时@A距离Foo.class的距离是 0 ，即@A 是在 Foo.class 上直接声明的，而@B距离Foo.class的距离就是 1 ，当 @A 与 @B 二选一的时候，距离更近的@A的权重就更高，换而言之，就是更匹配
 *
 * @author Phillip Webb
 * @since 5.2
 * @param <A> the annotation type
 * @see MergedAnnotationSelectors
 */
@FunctionalInterface
public interface MergedAnnotationSelector<A extends Annotation> {

	/**
	 * Determine if the existing annotation is known to be the best
	 * candidate and any subsequent selections may be skipped.
	 * @param annotation the annotation to check
	 * @return {@code true} if the annotation is known to be the best candidate
	 */
	default boolean isBestCandidate(MergedAnnotation<A> annotation) {
		return false;
	}

	/**
	 * Select the annotation that should be used.
	 * @param existing an existing annotation returned from an earlier result
	 * @param candidate a candidate annotation that may be better suited
	 * @return the most appropriate annotation from the {@code existing} or
	 * {@code candidate}
	 */
	MergedAnnotation<A> select(MergedAnnotation<A> existing, MergedAnnotation<A> candidate);

}
