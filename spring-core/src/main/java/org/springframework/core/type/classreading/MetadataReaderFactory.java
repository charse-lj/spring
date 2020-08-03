/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.core.type.classreading;

import java.io.IOException;

import org.springframework.core.io.Resource;

/**
 * Factory interface for {@link MetadataReader} instances.
 * Allows for caching a MetadataReader per original resource.
 *
 * @author Juergen Hoeller
 * @since 2.5
 * @see SimpleMetadataReaderFactory
 * @see CachingMetadataReaderFactory
 *
 * 对于MetadataReaderFactory的应用主要体现在几个地方
 * 1.ConfigurationClassPostProcessor：该属性值最终会传给ConfigurationClassParser，用于@EnableXXX / @Import等注解的解析上
 * 2.ClassPathScanningCandidateComponentProvider：它用于@ComponentScan的时候解析，拿到元数据判断是否是@Component的派生注解
 * 3.Mybatis的SqlSessionFactoryBean：它在使用上非常简单，只是为了从Resouece里拿到ClassName而已。classMetadata.getClassName()
 * 4.SourceClass：它是对source对象一个轻量级的包装，持有AnnotationMetadata 元数据，如下一般实际为一个StandardAnnotationMetadata，比如@EnableTransactionManagement用的就是它
 */
public interface MetadataReaderFactory {

	/**
	 * Obtain a MetadataReader for the given class name.
	 * @param className the class name (to be resolved to a ".class" file)
	 * @return a holder for the ClassReader instance (never {@code null})
	 * @throws IOException in case of I/O failure
	 */
	MetadataReader getMetadataReader(String className) throws IOException;

	/**
	 * Obtain a MetadataReader for the given resource.
	 * @param resource the resource (pointing to a ".class" file)
	 * @return a holder for the ClassReader instance (never {@code null})
	 * @throws IOException in case of I/O failure
	 */
	MetadataReader getMetadataReader(Resource resource) throws IOException;

}
