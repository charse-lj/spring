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

package org.springframework.beans.factory.config;

import org.springframework.beans.BeansException;
import org.springframework.lang.Nullable;

/**
 * Factory hook that allows for custom modification of new bean instances &mdash;
 * for example, checking for marker interfaces or wrapping beans with proxies.
 *
 * <p>Typically, post-processors that populate beans via marker interfaces
 * or the like will implement {@link #postProcessBeforeInitialization},
 * while post-processors that wrap beans with proxies will normally
 * implement {@link #postProcessAfterInitialization}.
 *
 * <h3>Registration</h3>
 * <p>An {@code ApplicationContext} can autodetect {@code BeanPostProcessor} beans
 * in its bean definitions and apply those post-processors to any beans subsequently
 * created. A plain {@code BeanFactory} allows for programmatic registration of
 * post-processors, applying them to all beans created through the bean factory.
 *
 * <h3>Ordering</h3>
 * <p>{@code BeanPostProcessor} beans that are autodetected in an
 * {@code ApplicationContext} will be ordered according to
 * {@link org.springframework.core.PriorityOrdered} and
 * {@link org.springframework.core.Ordered} semantics. In contrast,
 * {@code BeanPostProcessor} beans that are registered programmatically with a
 * {@code BeanFactory} will be applied in the order of registration; any ordering
 * semantics expressed through implementing the
 * {@code PriorityOrdered} or {@code Ordered} interface will be ignored for
 * programmatically registered post-processors. Furthermore, the
 * {@link org.springframework.core.annotation.Order @Order} annotation is not
 * taken into account for {@code BeanPostProcessor} beans.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 10.10.2003
 * @see InstantiationAwareBeanPostProcessor
 * @see DestructionAwareBeanPostProcessor
 * @see ConfigurableBeanFactory#addBeanPostProcessor
 * @see BeanFactoryPostProcessor
 *
 * 五个接口十个扩展点
 * 1、BeanPostProcessor: Bean后置处理器（和初始化相关）
 * postProcessBeforeInitialization():在每个bean创建之后、任何显示初始化方法调用之前工作,例如三种方式，定义的init方法，@PostConstruct，接口InitializingBean的方法
 * 	例子
 * 	 1). BeanValidationPostProcessor 完成JSR-303 @Valid注解Bean验证
 * 	 2). InitDestroyAnnotationBeanPostProcessor 完成@PostConstruct注解的初始化方法调用(所以它是在@Bean指定初始化方法调用之前执行的)
 * 	 3). ApplicationContextAwareProcessor 完成一些Aware接口的注入（如EnvironmentAware、ResourceLoaderAware、ApplicationContextAware）
 * postProcessAfterInitialization():每个bean初始化都完全完毕了。做一些事情
 *   1). AspectJAwareAdvisorAutoProxyCreator：完成xml风格的AOP配置(aop:config)的目标对象包装到AOP代理对象
 *   2). AnnotationAwareAspectJAutoProxyCreator：完成@Aspectj注解风格（aop:aspectj-autoproxy @Aspect）的AOP配置的目标对象包装到AOP代理对象
 * 2、MergedBeanDefinitionPostProcessor 合并Bean定义（继承BeanPostProcessor）
 * postProcessMergedBeanDefinition()：执行Bean定义的合并
 * 3、InstantiationAwareBeanPostProcessor 实例化Bean（继承BeanPostProcessor）
 * postProcessBeforeInstantiation()：实例化之前执行。给调用者一个机会，返回一个代理对象（相当于可以摆脱Spring的束缚，可以自定义实例化逻辑） 若返回null，继续后续Spring的逻辑。若返回不为null，就最后面都仅仅只执行 BeanPostProcessor #postProcessAfterInitialization这一个回调方法了
 *   1). 当AbstractAutoProxyCreator的实现者注册了TargetSourceCreator（创建自定义的TargetSource）将会按照这个流程去执行。绝大多数情况下调用者不会自己去实现TargetSourceCreator，而是Spring采用默认的SingletonTargetSource去生产AOP对象。 当然除了SingletonTargetSource，我们还可以使用ThreadLocalTargetSource（线程绑定的Bean）、CommonsPoolTargetSource（实例池的Bean）等等
 * postProcessAfterInitialization()：实例化完毕后、初始化之前执行。 若方法返回false，表示后续的InstantiationAwareBeanPostProcessor都不用再执行了。(一般不建议去返回false，它的意义在于若返回fasle不仅后续的不执行了，就连自己个的且包括后续的处理器的postProcessPropertyValues方法都将不会再执行了） populateBean()的时候调用，若有返回false，下面的postProcessPropertyValues就都不会调用了
 * postProcessPropertyValues()：紧接着上面postProcessAfterInitialization执行的（false解释如上）。如
 *   1). AutowiredAnnotationBeanPostProcessor执行@Autowired注解注入
 *   2). CommonAnnotationBeanPostProcessor执行@Resource等注解的注入，
 *   3). PersistenceAnnotationBeanPostProcessor执行@ PersistenceContext等JPA注解的注入，
 *   4). RequiredAnnotationBeanPostProcessor执行@ Required注解的检查等等
 * 4、SmartInstantiationAwareBeanPostProcessor 智能实例化Bean（继承InstantiationAwareBeanPostProcessor）
 * predictBeanType()：预测Bean的类型，返回第一个预测成功的Class类型，如果不能预测返回null； 当你调用BeanFactory.getType(name)时当通过Bean定义无法得到Bean类型信息时就调用该回调方法来决定类型信息。方法：getBeanNamesForType(,)会循环调用此方法~~~ 如：
 *   1). BeanFactory.isTypeMatch(name, targetType)用于检测给定名字的Bean是否匹配目标类型（在依赖注入时需要使用）
 * determineCandidateConstructors()：检测Bean的构造器，可以检测出多个候选构造器，再有相应的策略决定使用哪一个。 在createBeanInstance的时候，会通过此方法尝试去找到一个合适的构造函数。若返回null，可能就直接使用空构造函数去实例化了 如：
 *   1). AutowiredAnnotationBeanPostProcessor：它会扫描Bean中使用了@Autowired/@Value注解的构造器从而可以完成构造器注入
 * getEarlyBeanReference()：和循环引用相关了。当正在创建A时，A依赖B。此时会：将A作为ObjectFactory放入单例工厂中进行early expose，此处又需要引用A，但A正在创建，从单例工厂拿到ObjectFactory**（其通过getEarlyBeanReference获取及早暴露Bean)**从而允许循环依赖。
 *   1). AspectJAwareAdvisorAutoProxyCreator或AnnotationAwareAspectJAutoProxyCreator他们都有调用此方法，通过early reference能得到正确的代理对象。 有个小细节：这两个类中若执行了getEarlyBeanReference，那postProcessAfterInitialization就不会再执行了。
 * 5、DestructionAwareBeanPostProcessor 销毁Bean（继承BeanPostProcessor）
 * postProcessBeforeDestruction：销毁后处理回调方法，该回调只能应用到单例Bean。如
 *   1).InitDestroyAnnotationBeanPostProcessor完成@PreDestroy注解的销毁方法调用
 *
 *
 * 1.如果使用BeanFactory实现，非ApplicationContext实现，BeanPostProcessor执行顺序就是添加顺序。
 * 2.如果使用的是AbstractApplicationContext（实现了ApplicationContext）的实现，则通过如下规则指定顺序。
 * PriorityOrdered > Ordered > 无实现接口的 > 内部Bean后处理器（实现了MergedBeanDefinitionPostProcessor接口的是内部Bean PostProcessor，将在最后且无序注册）
 *
 * 接口中两个方法不能返回null，如果返回null那么在后续初始化方法将报空指针异常或者通过getBean()方法获取不到bena实例对象 ，因为后置处理器从Spring IoC容器中取出bean实例对象没有再次放回IoC容器中
 */
public interface BeanPostProcessor {

	/**
	 * Apply this {@code BeanPostProcessor} to the given new bean instance <i>before</i> any bean
	 * initialization callbacks (like InitializingBean's {@code afterPropertiesSet}
	 * or a custom init-method). The bean will already be populated with property values.
	 * The returned bean instance may be a wrapper around the original.
	 * <p>The default implementation returns the given {@code bean} as-is.
	 * @param bean the new bean instance
	 * @param beanName the name of the bean
	 * @return the bean instance to use, either the original or a wrapped one;
	 * if {@code null}, no subsequent BeanPostProcessors will be invoked
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 *
	 *
	 * BeanFactory和ApplicationContext注册Bean的后置处理器不通点：
	 * ApplicationContext直接使用@Bean注解，就能向容器注册一个后置处理器。
	 * 原因：它注册Bean的时候，会先检测是否实现了BeanPostProcessor接口，并自动把它们注册为后置处理器。所在在它这部署一个后置处理器和注册一个普通的Bean，是没有区别的
	 * BeanFactory必须显示的调用：void addBeanPostProcessor(BeanPostProcessor beanPostProcessor才能注册进去。
	 * Spring 可以注册多个Bean的后置处理器，是按照注册的顺序进行调用的。若想定制顺序，可以实现@Order或者实现Order接口~
	 */

	/**
	 *  在Bean实例化/依赖注入完毕以及自定义的初始化方法之前调用。
	 *  什么叫自定义初始化方法：比如init-method、比如@PostConstruct标、比如实现InitailztingBean接口的方法等等
	 * @param bean
	 * @param beanName 这个Bean实例  beanName：bean名称
	 * @return
	 * @throws BeansException
	 */
	@Nullable
	default Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	/**
	 * Apply this {@code BeanPostProcessor} to the given new bean instance <i>after</i> any bean
	 * initialization callbacks (like InitializingBean's {@code afterPropertiesSet}
	 * or a custom init-method). The bean will already be populated with property values.
	 * The returned bean instance may be a wrapper around the original.
	 * <p>In case of a FactoryBean, this callback will be invoked for both the FactoryBean
	 * instance and the objects created by the FactoryBean (as of Spring 2.0). The
	 * post-processor can decide whether to apply to either the FactoryBean or created
	 * objects or both through corresponding {@code bean instanceof FactoryBean} checks.
	 * <p>This callback will also be invoked after a short-circuiting triggered by a
	 * {@link InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation} method,
	 * in contrast to all other {@code BeanPostProcessor} callbacks.
	 * <p>The default implementation returns the given {@code bean} as-is.
	 * @param bean the new bean instance
	 * @param beanName the name of the bean
	 * @return the bean instance to use, either the original or a wrapped one;
	 * if {@code null}, no subsequent BeanPostProcessors will be invoked
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 * @see org.springframework.beans.factory.FactoryBean
	 *
	 *  postProcessAfterInitialization在每个bean的初始化方法（例如上面的三种方式，@Bean指定初始化方法，@PostConstruct，接口InitializingBean的方法）调用之后工作
	 *  该方法通常用于修改预定义的bean的属性值，可以实现属性覆盖。（该方法特别的重要，可以做一些全局统一处理的操作）
	 * 整个bean初始化都完全完毕了。做一些事情
	 * 1. AspectJAwareAdvisorAutoProxyCreator 完成xml风格的AOP配置(aop:config)的目标对象包装到AOP代理对象
	 * 2. AnnotationAwareAspectJAutoProxyCreator 完成@Aspectj注解风格（aop:aspectj-autoproxy @Aspect）的AOP配置的目标对象包装到AOP代理对象
	 *
	 * 在上面基础上，初始化方法之后调用
	 */
	@Nullable
	default Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

}
