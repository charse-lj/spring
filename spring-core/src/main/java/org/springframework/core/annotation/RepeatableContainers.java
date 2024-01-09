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

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Method;
import java.util.Map;

 /**
 * Strategy used to determine annotations that act as containers for other
 * annotations. The {@link #standardRepeatables()} method provides a default
 * strategy that respects Java's {@link Repeatable @Repeatable} support and
 * should be suitable for most situations.
 *
 * <p>The {@link #of} method can be used to register relationships for
 * annotations that do not wish to use {@link Repeatable @Repeatable}.
 *
 * <p>To completely disable repeatable support use {@link #none()}.
 * <p>
 * 可重复注解容器 -->表示某个可重复注解与他的某个容器注解之间的对应关系
  *
  * 可重复的注解
  * @Repeatable(RepeatableContainerAnnotation.class)
  * @interface RepeatableAnnotation {}
  *
  * 可重复注解的容器注解
  * @interface RepeatableContainerAnnotation {
  *     RepeatableAnnotation[] value() default {};
  * }
  *
  * 所有重复注解都收集到容器中.
 *
 * @author Phillip Webb
 * @since 5.2
 */
public abstract class RepeatableContainers {

	 /**
	  * 一个树结构，通过 parent 变量持有当前容器注解与容器注解的容器注解的对应关系。
	  */
	@Nullable
	private final RepeatableContainers parent;


	private RepeatableContainers(@Nullable RepeatableContainers parent) {
		this.parent = parent;
	}


	/**
	 * Add an additional explicit relationship between a contained and
	 * repeatable annotation.
	 *
	 * @param container  the container type
	 * @param repeatable the contained repeatable type
	 * @return a new {@link RepeatableContainers} instance
	 * <p>
	 * 一个可重复注解和其注解容器间建立关系
	 */
	public RepeatableContainers and(Class<? extends Annotation> container,
									Class<? extends Annotation> repeatable) {

		return new ExplicitRepeatableContainer(this, repeatable, container);
	}

	/**
	 * 查找可重复注解
	 *
	 * @param annotation .
	 * @return .
	 */
	@Nullable
	Annotation[] findRepeatedAnnotations(Annotation annotation) {
		if (this.parent == null) {
			return null;
		}
		// 返回父节点的findRepeatedAnnotations
		return this.parent.findRepeatedAnnotations(annotation);
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (other == this) {
			return true;
		}
		if (other == null || getClass() != other.getClass()) {
			return false;
		}
		return ObjectUtils.nullSafeEquals(this.parent, ((RepeatableContainers) other).parent);
	}

	@Override
	public int hashCode() {
		return ObjectUtils.nullSafeHashCode(this.parent);
	}


	/**
	 * Create a {@link RepeatableContainers} instance that searches using Java's
	 * {@link Repeatable @Repeatable} annotation.
	 *
	 * @return a {@link RepeatableContainers} instance
	 */
	public static RepeatableContainers standardRepeatables() {
		return StandardRepeatableContainers.INSTANCE;
	}

	/**
	 * Create a {@link RepeatableContainers} instance that uses a defined
	 * container and repeatable type.
	 *
	 * @param repeatable the contained repeatable annotation
	 * @param container  the container annotation or {@code null}. If specified,
	 *                   this annotation must declare a {@code value} attribute returning an array
	 *                   of repeatable annotations. If not specified, the container will be
	 *                   deduced by inspecting the {@code @Repeatable} annotation on
	 *                   {@code repeatable}.
	 * @return a {@link RepeatableContainers} instance
	 */
	public static RepeatableContainers of(
			Class<? extends Annotation> repeatable, @Nullable Class<? extends Annotation> container) {

		return new ExplicitRepeatableContainer(null, repeatable, container);
	}

	/**
	 * Create a {@link RepeatableContainers} instance that does not expand any
	 * repeatable annotations.
	 *
	 * @return a {@link RepeatableContainers} instance
	 */
	public static RepeatableContainers none() {
		return NoRepeatableContainers.INSTANCE;
	}


	/**
	 * Standard {@link RepeatableContainers} implementation that searches using
	 * Java's {@link Repeatable @Repeatable} annotation.
	 */
	private static class StandardRepeatableContainers extends RepeatableContainers {

		private static final Map<Class<? extends Annotation>, Object> cache = new ConcurrentReferenceHashMap<>();

		private static final Object NONE = new Object();

		private static StandardRepeatableContainers INSTANCE = new StandardRepeatableContainers();

		StandardRepeatableContainers() {
			super(null);
		}

		/**
		 *
		 * @param annotation 待查找的注解对象,从该注解上查找重复注解容器.
		 * @return
		 */
		@Override
		@Nullable
		Annotation[] findRepeatedAnnotations(Annotation annotation) {
			Method method = getRepeatedAnnotationsMethod(annotation.annotationType());
			if (method != null) {
				return (Annotation[]) ReflectionUtils.invokeMethod(method, annotation);
			}
			return super.findRepeatedAnnotations(annotation);
		}

		@Nullable
		private static Method getRepeatedAnnotationsMethod(Class<? extends Annotation> annotationType) {
			Object result = cache.computeIfAbsent(annotationType,
					StandardRepeatableContainers::computeRepeatedAnnotationsMethod);
			return (result != NONE ? (Method) result : null);
		}

		/**
		 * @param annotationType 待检查的注解类.
		 * @return .
		 */
		private static Object computeRepeatedAnnotationsMethod(Class<? extends Annotation> annotationType) {
			//获取所注解类的所有注解属性方法
			AttributeMethods methods = AttributeMethods.forAnnotationType(annotationType);
			//只有一个名为value的属性
			if (methods.hasOnlyValueAttribute()) {
				Method method = methods.get(0);
				//方法返回值
				Class<?> returnType = method.getReturnType();
				//返回值是可重复注解类型的数组，并且可重复注解上存在@Repeatable注解
				if (returnType.isArray()) {
					//获取数组元素
					Class<?> componentType = returnType.getComponentType();
					//类必须是一个注解类
					if (Annotation.class.isAssignableFrom(componentType) &&
							//注解类上必须包含@Repeatable注解
							componentType.isAnnotationPresent(Repeatable.class)) {
						return method;
					}
				}
			}
			return NONE;
		}
	}


	/**
	 * A single explicit mapping.
	 */
	private static class ExplicitRepeatableContainer extends RepeatableContainers {

 		/**
		 * 可重复的注解
		 */
		private final Class<? extends Annotation> repeatable;

 		/**
		 * 容器注解
		 */
		private final Class<? extends Annotation> container;

		/**
		 * 容器注解的value方法
		 */
		private final Method valueMethod;

		ExplicitRepeatableContainer(@Nullable RepeatableContainers parent,
									Class<? extends Annotation> repeatable, @Nullable Class<? extends Annotation> container) {

			super(parent);
			Assert.notNull(repeatable, "Repeatable must not be null");
			if (container == null) {
				//推断容器类
				container = deduceContainer(repeatable);
			}
			//获取名为value()的方法
			Method valueMethod = AttributeMethods.forAnnotationType(container).get(MergedAnnotation.VALUE);
			//校验
			try {
				//不能为空
				if (valueMethod == null) {
					throw new NoSuchMethodException("No value method found");
				}
				//获取返回值
				Class<?> returnType = valueMethod.getReturnType();
				//返回值必须是数组,且其中元素类型有要求
				if (!returnType.isArray() || returnType.getComponentType() != repeatable) {
					throw new AnnotationConfigurationException("Container type [" +
							container.getName() +
							"] must declare a 'value' attribute for an array of type [" +
							repeatable.getName() + "]");
				}
			} catch (AnnotationConfigurationException ex) {
				throw ex;
			} catch (Throwable ex) {
				throw new AnnotationConfigurationException(
						"Invalid declaration of container type [" + container.getName() +
								"] for repeatable annotation [" + repeatable.getName() + "]",
						ex);
			}
			this.repeatable = repeatable;
			this.container = container;
			this.valueMethod = valueMethod;
		}

		/**
		 * @param repeatable 含有@Repeatable注解的注解元素类.
		 * @return .
		 */
		private Class<? extends Annotation> deduceContainer(Class<? extends Annotation> repeatable) {
			//获取Repeatable注解对象
			Repeatable annotation = repeatable.getAnnotation(Repeatable.class);
			Assert.notNull(annotation, () -> "Annotation type must be a repeatable annotation: " +
					"failed to resolve container type for " + repeatable.getName());
			return annotation.value();
		}

		@Override
		@Nullable
		Annotation[] findRepeatedAnnotations(Annotation annotation) {
			// 若容器注解的value方法返回值就是可重复注解，说明容器注解就是该可重复注解的直接容器
			if (this.container.isAssignableFrom(annotation.annotationType())) {
				//在这个对象上执行方法.
				return (Annotation[]) ReflectionUtils.invokeMethod(this.valueMethod, annotation);
			}
			// 否则说明存在嵌套结构，当前容器注解实际上放的也是一个容器注解，继续递归直到找到符合条件的容器注解为止
			return super.findRepeatedAnnotations(annotation);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (!super.equals(other)) {
				return false;
			}
			ExplicitRepeatableContainer otherErc = (ExplicitRepeatableContainer) other;
			return (this.container.equals(otherErc.container) && this.repeatable.equals(otherErc.repeatable));
		}

		@Override
		public int hashCode() {
			int hashCode = super.hashCode();
			hashCode = 31 * hashCode + this.container.hashCode();
			hashCode = 31 * hashCode + this.repeatable.hashCode();
			return hashCode;
		}
	}


	/**
	 * No repeatable containers.
	 */
	private static class NoRepeatableContainers extends RepeatableContainers {

		private static NoRepeatableContainers INSTANCE = new NoRepeatableContainers();

		NoRepeatableContainers() {
			super(null);
		}
	}

}
