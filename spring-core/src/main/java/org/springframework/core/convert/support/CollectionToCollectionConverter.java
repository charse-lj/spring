/*
 * Copyright 2002-2014 the original author or authors.
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

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.springframework.core.CollectionFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.lang.Nullable;

/**
 * Converts from a Collection to another Collection.
 *
 * <p>First, creates a new Collection of the requested targetType with a size equal to the
 * size of the source Collection. Then copies each element in the source collection to the
 * target collection. Will perform an element conversion from the source collection's
 * parameterized type to the target collection's parameterized type if necessary.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @since 3.0
 */
final class CollectionToCollectionConverter implements ConditionalGenericConverter {

	private final ConversionService conversionService;


	public CollectionToCollectionConverter(ConversionService conversionService) {
		this.conversionService = conversionService;
	}


	@Override
	public Set<ConvertiblePair> getConvertibleTypes() {
		return Collections.singleton(new ConvertiblePair(Collection.class, Collection.class));
	}

	@Override
	public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
		return ConversionUtils.canConvertElements(
				sourceType.getElementTypeDescriptor(), targetType.getElementTypeDescriptor(), this.conversionService);
	}

	/**
	 * 该转换步骤稍微有点复杂，我帮你屡清楚后有这几个关键步骤：
	 *
	 * 快速返回：对于特殊情况，做快速返回处理
	 * 		若目标元素类型是源元素类型的子类型（或相同），就没有转换的必要了（copyRequired = false）
	 * 		若源集合为空，或者目标集合没指定泛型，也不需要做转换动作
	 * 			源集合为空，还转换个啥
	 * 			目标集合没指定泛型，那就是Object，因此可以接纳一切，还转换个啥
	 * 	若没有触发快速返回。给目标创建一个新集合，然后把source的元素一个一个的放进新集合里去，这里又分为两种处理case
	 * 		若新集合（目标集合）没有指定泛型类型（那就是Object），就直接putAll即可，并不需要做类型转换
	 * 		若新集合（目标集合指定了泛型类型），就遍历源集合委托conversionService.convert()对元素一个一个的转
	 * @param source the source object to convert (may be {@code null})
	 * @param sourceType the type descriptor of the field we are converting from
	 * @param targetType the type descriptor of the field we are converting to
	 * @return
	 */
	@Override
	@Nullable
	public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
		if (source == null) {
			return null;
		}
		Collection<?> sourceCollection = (Collection<?>) source;

		// Shortcut if possible...
		//target是否不是source的子类
		boolean copyRequired = !targetType.getType().isInstance(source);
		if (!copyRequired && sourceCollection.isEmpty()) {
			//是，source是空的,返回source
			return source;
		}
		TypeDescriptor elementDesc = targetType.getElementTypeDescriptor();
		if (elementDesc == null && !copyRequired) {
			//是,elementDesc是null，也返回source
			return source;
		}

		// At this point, we need a collection copy in any case, even if just for finding out about element copies...
		//构造装target元素的容器
		Collection<Object> target = CollectionFactory.createCollection(targetType.getType(),
				(elementDesc != null ? elementDesc.getType() : null), sourceCollection.size());

		//target元素类型是空的
		if (elementDesc == null) {
			target.addAll(sourceCollection);
		}
		else {
			//遍历source中的元素
			for (Object sourceElement : sourceCollection) {
				//根据sourceElement、sourceType、targetType,找到合适的转化器进行转换
				Object targetElement = this.conversionService.convert(sourceElement,
						sourceType.elementTypeDescriptor(sourceElement), elementDesc);
				target.add(targetElement);
				if (sourceElement != targetElement) {
					copyRequired = true;
				}
			}
		}

		return (copyRequired ? target : source);
	}

}
