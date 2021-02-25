/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.core.convert.support;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;

import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Converts an entity identifier to a entity reference by calling a static finder method
 * on the target entity type.
 *
 * <p>For this converter to match, the finder method must be static, have the signature
 * {@code find[EntityName]([IdType])}, and return an instance of the desired entity type.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @since 3.0
 *
 *
 * 通过调用静态查找方法将实体ID兑换为实体对象,满足的条件
 * 1.必须是static静态方法
 * 2.方法名必须为find + entityName。如Person类的话，那么方法名叫findPerson
 * 3.方法参数列表必须为1个
 * 4.返回值类型必须是Entity类型
 */
final class IdToEntityConverter implements ConditionalGenericConverter {

	private final ConversionService conversionService;


	public IdToEntityConverter(ConversionService conversionService) {
		this.conversionService = conversionService;
	}


	@Override
	public Set<ConvertiblePair> getConvertibleTypes() {
		//ID和Entity都可以是任意类型
		return Collections.singleton(new ConvertiblePair(Object.class, Object.class));
	}

	@Override
	public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
		Method finder = getFinder(targetType.getType());
		//存在符合条件的find方法，且source可以转换为ID类型（注意source能转换成id类型就成，并非目标类型哦）
		return (finder != null &&
				this.conversionService.canConvert(sourceType, TypeDescriptor.valueOf(finder.getParameterTypes()[0])));
	}

	@Override
	@Nullable
	public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
		if (source == null) {
			return null;
		}
		Method finder = getFinder(targetType.getType());
		Assert.state(finder != null, "No finder method");
		Object id = this.conversionService.convert(
				source, sourceType, TypeDescriptor.valueOf(finder.getParameterTypes()[0]));
		return ReflectionUtils.invokeMethod(finder, source, id);
	}

	@Nullable
	private Method getFinder(Class<?> entityClass) {
		String finderMethod = "find" + getEntityName(entityClass);
		Method[] methods;
		boolean localOnlyFiltered;
		try {
			methods = entityClass.getDeclaredMethods();
			localOnlyFiltered = true;
		}
		catch (SecurityException ex) {
			// Not allowed to access non-public methods...
			// Fallback: check locally declared public methods only.
			methods = entityClass.getMethods();
			localOnlyFiltered = false;
		}
		for (Method method : methods) {
			if (Modifier.isStatic(method.getModifiers()) && method.getName().equals(finderMethod) &&
					method.getParameterCount() == 1 && method.getReturnType().equals(entityClass) &&
					(localOnlyFiltered || method.getDeclaringClass().equals(entityClass))) {
				return method;
			}
		}
		return null;
	}

	private String getEntityName(Class<?> entityClass) {
		String shortName = ClassUtils.getShortName(entityClass);
		int lastDot = shortName.lastIndexOf('.');
		if (lastDot != -1) {
			return shortName.substring(lastDot + 1);
		}
		else {
			return shortName;
		}
	}

}
