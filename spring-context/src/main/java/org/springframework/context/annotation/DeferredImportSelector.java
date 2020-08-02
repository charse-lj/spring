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

package org.springframework.context.annotation;

import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;

/**
 * A variation of {@link ImportSelector} that runs after all {@code @Configuration} beans
 * have been processed. This type of selector can be particularly useful when the selected
 * imports are {@code @Conditional}.
 *
 * <p>Implementations can also extend the {@link org.springframework.core.Ordered}
 * interface or use the {@link org.springframework.core.annotation.Order} annotation to
 * indicate a precedence against other {@link DeferredImportSelector DeferredImportSelectors}.
 *
 * <p>Implementations may also provide an {@link #getImportGroup() import group} which
 * can provide additional sorting and filtering logic across different selectors.
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @since 4.0
 *
 * 从现象已经和名字中，我们能够更加直观的看出来：DeferredImportSelector显然是属于延迟加载、靠后加载的，那到底有多延迟，他们执行时机都是啥时候呢
 *
 * DeferredImportSelector是ImportSelector的一个扩展
 * ImportSelector实例的selectImports方法的执行时机，是在@Configguration注解中的其他逻辑被处理**之前**，所谓的其他逻辑，包括对@ImportResource、@Bean这些注解的处理（注意，这里只是对@Bean修饰的方法的处理，并不是立即调用@Bean修饰的方法，这个区别很重要！）
 * DeferredImportSelector实例的selectImports方法的执行时机，是在@Configguration注解中的其他逻辑被处理**完毕之后**
 * DeferredImportSelector的实现类可以用Order注解，或者实现Ordered接口来对selectImports的执行顺序排序（ImportSelector不支持）
 * Spring Boot的自动配置功能就是通过DeferredImportSelector接口的实现类EnableAutoConfigurationImportSelector做到的（因为自动配置必须在我们自定义配置后执行才行）
 *
 * ImportSelector 被设计成其实和@Import注解的类同样的导入效果，但是实现 ImportSelector的类可以条件性地决定导入哪些配置。
 * DeferredImportSelector 的设计目的是在所有其他的配置类被处理后才处理。这也正是该语句被放到本函数最后一行的原因
 *
 */
public interface DeferredImportSelector extends ImportSelector {

	/**
	 * Return a specific import group.
	 * <p>The default implementations return {@code null} for no grouping required.
	 * @return the import group class, or {@code null} if none
	 * @since 5.0
	 */
	@Nullable
	default Class<? extends Group> getImportGroup() {
		return null;
	}


	/**
	 * Interface used to group results from different import selectors.
	 * @since 5.0
	 *
	 */
	interface Group {

		/**
		 * Process the {@link AnnotationMetadata} of the importing @{@link Configuration}
		 * class using the specified {@link DeferredImportSelector}.
		 */
		void process(AnnotationMetadata metadata, DeferredImportSelector selector);

		/**
		 * Return the {@link Entry entries} of which class(es) should be imported
		 * for this group.
		 *
		 * 内部接口
		 */
		Iterable<Entry> selectImports();


		/**
		 * An entry that holds the {@link AnnotationMetadata} of the importing
		 * {@link Configuration} class and the class name to import.
		 *  内部的内部类
		 */
		class Entry {

			private final AnnotationMetadata metadata;

			private final String importClassName;

			public Entry(AnnotationMetadata metadata, String importClassName) {
				this.metadata = metadata;
				this.importClassName = importClassName;
			}

			/**
			 * Return the {@link AnnotationMetadata} of the importing
			 * {@link Configuration} class.
			 */
			public AnnotationMetadata getMetadata() {
				return this.metadata;
			}

			/**
			 * Return the fully qualified name of the class to import.
			 */
			public String getImportClassName() {
				return this.importClassName;
			}

			@Override
			public boolean equals(@Nullable Object other) {
				if (this == other) {
					return true;
				}
				if (other == null || getClass() != other.getClass()) {
					return false;
				}
				Entry entry = (Entry) other;
				return (this.metadata.equals(entry.metadata) && this.importClassName.equals(entry.importClassName));
			}

			@Override
			public int hashCode() {
				return (this.metadata.hashCode() * 31 + this.importClassName.hashCode());
			}

			@Override
			public String toString() {
				return this.importClassName;
			}
		}
	}

}
