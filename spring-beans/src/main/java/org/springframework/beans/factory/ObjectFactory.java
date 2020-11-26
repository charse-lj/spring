/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.beans.factory;

import org.springframework.beans.BeansException;

/**
 * Defines a factory which can return an Object instance
 * (possibly shared or independent) when invoked.
 *
 * <p>This interface is typically used to encapsulate a generic factory which
 * returns a new instance (prototype) of some target object on each invocation.
 *
 * <p>This interface is similar to {@link FactoryBean}, but implementations
 * of the latter are normally meant to be defined as SPI instances in a
 * {@link BeanFactory}, while implementations of this class are normally meant
 * to be fed as an API to other beans (through injection). As such, the
 * {@code getObject()} method has different exception handling behavior.
 *
 * @author Colin Sampaleanu
 * @since 1.0.2
 * @param <T> the object type
 * @see FactoryBean
 * 一个对象工厂
 * Spring中主要两处用了它
 * 1.Scope接口中的get方法，需要传入一个ObjectFactory
 * 		这个方法的目的就是从对于的域中获取到指定名称的对象。为什么要传入一个objectFactory呢？主要是为了方便我们扩展自定义的域，而不是仅仅使用request，session等域
 * 2.ConfigurableListableBeanFactory类中的registerResolvableDependency方法
 * 		Spring通过这种方式注入了request,response等对象
 * 		当我们在某一个类中如果注入了ServletRequest对象，并不会直接创建一个ServletRequest然后注入进去，而是注入一个代理类，代理类中的方法是通过ObjectFactoryDelegatingInvocationHandler实现的，而这个对象中会持有一个RequestObjectFactory对象。基于此，我们可以通过下面这种方式直接注入request对象，并且保证线程安全
 * @RestController
 * public class AutowiredRequestController {
 *
 *     @Autowired
 *     private HttpServletRequest request;
 * }
 *
 */
@FunctionalInterface
public interface ObjectFactory<T> {

	/**
	 * Return an instance (possibly shared or independent)
	 * of the object managed by this factory.
	 * @return the resulting instance
	 * @throws BeansException in case of creation errors
	 */
	T getObject() throws BeansException;

}
