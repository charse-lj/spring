/*
 * Copyright 2002-2016 the original author or authors.
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

package org.springframework.beans.factory.config;

import java.lang.reflect.Constructor;

import org.springframework.beans.BeansException;
import org.springframework.lang.Nullable;

/**
 * Extension of the {@link InstantiationAwareBeanPostProcessor} interface,
 * adding a callback for predicting the eventual type of a processed bean.
 *
 * <p><b>NOTE:</b> This interface is a special purpose interface, mainly for
 * internal use within the framework. In general, application-provided
 * post-processors should simply implement the plain {@link BeanPostProcessor}
 * interface or derive from the {@link InstantiationAwareBeanPostProcessorAdapter}
 * class. New methods might be added to this interface even in point releases.
 *
 * @author Juergen Hoeller
 * @since 2.0.3
 * @see InstantiationAwareBeanPostProcessorAdapter
 * 智能实例化Bean
 * 比如AutowiredAnnotationBeanPostProcessor：依赖注入时的泛型依赖注入，就通过这个能智能判断类型来注入。
 * 泛型依赖注入的优点：允许我们在使用spring进行依赖注入的同时，利用泛型的优点对代码进行精简，将可重复使用的代码全部放到一个类之中，方便以后的维护和修改。比如常用的Base设计。。。（属于Spring 4.0的新特性）
 *
 * @Configuration
 * public class MyConfiguration {
 *        @Bean
 *    public BaseRepository<Student> studentRepository() {
 * 		return new BaseRepository<Student>() {};
 *    }
 *
 *    @Bean
 *    public BaseRepository<Faculty> facultyRepository() {
 * 		return new BaseRepository<Faculty>() {};
 *    }
 * }
 *  注意，这里能够正确注入到正确的Bean，虽然他们都是BaseRepository类型。但是在Spring4.0之后，泛型里面的东西
 *  也可以作为一种分类Qualifier，随意这里虽然都是BaseRepositor类型，但是在容器中还是被分开了的
 */
public interface SmartInstantiationAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessor {

	/**
	 * Predict the type of the bean to be eventually returned from this
	 * processor's {@link #postProcessBeforeInstantiation} callback.
	 * <p>The default implementation returns {@code null}.
	 * @param beanClass the raw class of the bean
	 * @param beanName the name of the bean
	 * @return the type of the bean, or {@code null} if not predictable
	 * @throws org.springframework.beans.BeansException in case of errors
	 *
	 *  predictBeanType：预测Bean的类型，返回第一个预测成功的Class类型，如果不能预测返回null；
	 *  当你调用BeanFactory.getType(name)时,通过Bean定义无法得到Bean类型信息时就调用该回调方法来决定类型信息。
	 *  方法：getBeanNamesForType()会循环调用此方法~~~
	 *  如 BeanFactory.isTypeMatch(name, targetType)用于检测给定名字的Bean是否匹配目标类型（在依赖注入时需要使用）
	 *  如果目标对象被AOP代理对象包装，此处将返回AOP代理对象的类型（而不是目标对象的类型）
	 *
	 * 预测Bean的类型，主要是在Bean还没有创建前我们可以需要获取Bean的类型
	 */
	@Nullable
	default Class<?> predictBeanType(Class<?> beanClass, String beanName) throws BeansException {
		return null;
	}

	/**
	 * Determine the candidate constructors to use for the given bean.
	 * <p>The default implementation returns {@code null}.
	 * @param beanClass the raw class of the bean (never {@code null})
	 * @param beanName the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 *
	 * determineCandidateConstructors：检测Bean的构造器，可以检测出多个候选构造器，再有相应的策略决定使用哪一个
	 * 在createBeanInstance的时候，会通过此方法尝试去找到一个合适的构造函数。若返回null，可能就直接使用空构造函数去实例化了
	 * 如：AutowiredAnnotationBeanPostProcessor：它会扫描Bean中使用了@Autowired/@Value注解的构造器从而可以完成构造器注入
	 *
	 * Spring使用这个方法完成了构造函数的推断
	 */
	@Nullable
	default Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName)
			throws BeansException {

		return null;
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 * <p>This callback gives post-processors a chance to expose a wrapper
	 * early - that is, before the target bean instance is fully initialized.
	 * The exposed object should be equivalent to the what
	 * {@link #postProcessBeforeInitialization} / {@link #postProcessAfterInitialization}
	 * would expose otherwise. Note that the object returned by this method will
	 * be used as bean reference unless the post-processor returns a different
	 * wrapper from said post-process callbacks. In other words: Those post-process
	 * callbacks may either eventually expose the same reference or alternatively
	 * return the raw bean instance from those subsequent callbacks (if the wrapper
	 * for the affected bean has been built for a call to this method already,
	 * it will be exposes as final bean reference by default).
	 * <p>The default implementation returns the given {@code bean} as-is.
	 * @param bean the raw bean instance
	 * @param beanName the name of the bean
	 * @return the object to expose as bean reference
	 * (typically with the passed-in bean instance as default)
	 * @throws org.springframework.beans.BeansException in case of errors
	 *
	 *  getEarlyBeanReference：和循环引用相关了。
	 *  当正在创建A时，A依赖B。此时会：将A作为 ObjectFactory 放入单例工厂中进行early expose，此处又需要引用A，但A正在创建，从单例工厂拿到ObjectFactory（其通过getEarlyBeanReference获取及早暴露Bean)从而允许循环依赖
	 *  AspectJAwareAdvisorAutoProxyCreator或AnnotationAwareAspectJAutoProxyCreator他们都有调用此方法，通过early reference能得到正确的代理对象。
	 *  有个小细节：这两个类中若执行了getEarlyBeanReference，那postProcessAfterInitialization就不会再执行了。
	 *  getEarlyBeanReference和postProcessAfterInitialization是二者选一的，而且单例Bean目标对象只能被增强一次，而原型Bean目标对象可能被包装多次
	 */
	default Object getEarlyBeanReference(Object bean, String beanName) throws BeansException {
		return bean;
	}

}
