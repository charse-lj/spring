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

package org.springframework.beans.factory.config;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;

/**
 * A BeanDefinition describes a bean instance, which has property values,
 * constructor argument values, and further information supplied by
 * concrete implementations.
 * <p>
 * This is just a minimal interface: The main intention is to allow a
 * {@link BeanFactoryPostProcessor} to introspect and modify property values
 * and other bean metadata.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @see ConfigurableListableBeanFactory#getBeanDefinition
 * @see org.springframework.beans.factory.support.RootBeanDefinition
 * @see org.springframework.beans.factory.support.ChildBeanDefinition
 * @since 19.03.2004
 * <p>
 * BeanDefinition
 * 1.一个全限定类名，通常来说，就是对应的bean的全限定类名。
 * 2.bean的行为配置元素，这些元素展示了这个bean在容器中是如何工作的包括scope(域，我们文末有简单介绍)，lifecycle callbacks(生命周期回调)等等
 * 3.这个bean的依赖信息
 * 4.一些其他配置信息，比如我们配置了一个连接池对象，那么我们还会配置它的池子大小，最大连接数等等
 *
 *
 * Spring容器启动的过程中，会将Bean解析成Spring内部的BeanDefinition结构
 * 不管是是通过xml配置文件的<Bean>标签，还是通过注解配置的@Bean，它最终都会被解析成一个BeanDefinition,最后我们的Bean工厂就会根据这份Bean的定义信息，对bean进行实例化、初始化等等操作
 *
 * IoC容器想要管理各个业务对象以及它们之间的依赖关系，需要通过某种途径来记录和管理这些信息。 BeanDefinition对象就承担了这个责任
 * 说明：假如直接通过 SingletonBeanRegistry#registerSingleton向容器手动注入Bean的，那么就不会存在这份Bean定义信息
 *
 * 一个BeanDefinition包含了很多的配置信息，包括构造参数，setter方法的参数还有容器特定的一些配置信息，比如初始化方法，静态工厂方法等等。
 * 一个子的BeanDefinition可以从它的父BeanDefinition继承配置信息，不仅如此，还可以覆盖其中的一些值或者添加一些自己需要的属性。
 * 使用BeanDefinition的父子定义可以减少很多的重复属性的设置，父BeanDefinition可以作为BeanDefinition定义的模板
 * 子BeanDefinition会从父BeanDefinition中继承没有的属性。
 * 子BeanDefinition中已经存在的属性不会被父BeanDefinition中所覆盖。
 *
 * 合并需要注意的点
 * 子BeanDefinition中的class属性如果为null，同时父BeanDefinition又指定了class属性，那么子BeanDefinition也会继承这个class属性。
 * 子BeanDefinition必须要兼容父BeanDefinition中的所有属性。这是什么意思呢？以我们上面的demo为例，我们在父BeanDefinition中指定了name跟age属性，但是如果子BeanDefinition中子提供了一个name的setter方法，这个时候Spring在启动的时候会报错。因为子BeanDefinition不能承接所有来自父BeanDefinition的属性
 *
 * 整个Bean的生命周期可以分为以下几个阶段：
 * 1)实例化（得到一个还没有经过属性注入跟初始化的对象）
 * 2)属性注入（得到一个经过了属性注入但还没有初始化的对象）
 * 3)初始化（得到一个经过了初始化但还没有经过AOP的对象，AOP会在后置处理器中执行）
 * 		1.实现InitializingBean接口
 * 		2.使用Bean标签中的init-method属性
 * 		3.使用@PostConstruct注解
 * 		执行顺序-> 3、1、2
 * 4)销毁
 * 		1.实现DisposableBean接口
 * 		2.使用Bean标签中的destroy-method属性
 * 		3.使用@PreDestroy注解
 * 		执行顺序-> 3、1、2
 * 5)容器启动或停止回调 Lifecycle
 * 执行顺序：注解的优先级 > 实现接口的优先级 > XML配置的优先级
 * 官网推荐:使用注解的形式来定义生命周期回调方法，这是因为相比于实现接口，采用注解这种方式我们的代码跟Spring框架本身的耦合度更加低
 * 在上面几个阶段中，BeanPostProcessor将会穿插执行。而在初始化跟销毁阶段又分为两部分：
 * 生命周期回调方法的执行
 * aware相关接口方法的执行
 */
public interface BeanDefinition extends AttributeAccessor, BeanMetadataElement {

	/**
	 * Scope identifier for the standard singleton scope: {@value}.
	 * Note that extended bean factories might support further scopes.
	 *
	 * @see #setScope
	 * @see ConfigurableBeanFactory#SCOPE_SINGLETON
	 * 单例Bean
	 */
	String SCOPE_SINGLETON = ConfigurableBeanFactory.SCOPE_SINGLETON;

	/**
	 * Scope identifier for the standard prototype scope: {@value}.
	 * Note that extended bean factories might support further scopes.
	 *
	 * @see #setScope
	 * @see ConfigurableBeanFactory#SCOPE_PROTOTYPE
	 * 原型Bean
	 */
	String SCOPE_PROTOTYPE = ConfigurableBeanFactory.SCOPE_PROTOTYPE;

	/**
	 * Bean角色
	 * Role hint indicating that a {@code BeanDefinition} is a major part
	 * of the application. Typically corresponds to a user-defined bean.
	 * 用户定义的 Bean
	 */
	int ROLE_APPLICATION = 0;

	/**
	 * Role hint indicating that a {@code BeanDefinition} is a supporting
	 * part of some larger configuration, typically an outer
	 * {@link org.springframework.beans.factory.parsing.ComponentDefinition}.
	 * {@code SUPPORT} beans are considered important enough to be aware
	 * of when looking more closely at a particular
	 * {@link org.springframework.beans.factory.parsing.ComponentDefinition},
	 * but not when looking at the overall configuration of an application.
	 * 配置文件的 Bean
	 */
	int ROLE_SUPPORT = 1;

	/**
	 * Role hint indicating that a {@code BeanDefinition} is providing an
	 * entirely background role and has no relevance to the end-user. This hint is
	 * used when registering beans that are completely part of the internal workings
	 * of a {@link org.springframework.beans.factory.parsing.ComponentDefinition}.
	 *
	 * 指内部工作的基础构造  实际上是说我这Bean是Spring自己的，和你用户没有一毛钱关系
	 * Spring 内部的 Bean
	 */
	int ROLE_INFRASTRUCTURE = 2;


	// Modifiable attributes

	/**
	 * Set the name of the parent definition of this bean definition, if any.
	 * 若存在父类的话，就设置进去
	 */
	void setParentName(@Nullable String parentName);

	/**
	 * Return the name of the parent definition of this bean definition, if any.
	 *
	 * 获取父BeanDefinition,主要用于合并，下节中会详细分析
	 */
	@Nullable
	String getParentName();

	/**
	 * Specify the bean class name of this bean definition.
	 * The class name can be modified during bean factory post-processing,
	 * typically replacing the original class name with a parsed variant of it.
	 *
	 * @see #setParentName
	 * @see #setFactoryBeanName
	 * @see #setFactoryMethodName
	 *
	 * 指定Class类型。需要注意的是该类型还有可能被改变在Bean post-processing阶段
	 * 若是getFactoryBeanName  getFactoryMethodName这种情况下会改变
	 * 对于的bean的ClassName
	 */
	void setBeanClassName(@Nullable String beanClassName);

	/**
	 * Return the current bean class name of this bean definition.
	 * Note that this does not have to be the actual class name used at runtime, in
	 * case of a child definition overriding/inheriting the class name from its parent.
	 * Also, this may just be the class that a factory method is called on, or it may
	 * even be empty in case of a factory bean reference that a method is called on.
	 * Hence, do <i>not</i> consider this to be the definitive bean type at runtime but
	 * rather only use it for parsing purposes at the individual bean definition level.
	 *
	 * @see #getParentName()
	 * @see #getFactoryBeanName()
	 * @see #getFactoryMethodName()
	 */
	@Nullable
	String getBeanClassName();

	/**
	 * Override the target scope of this bean, specifying a new scope name.
	 *
	 * @see #SCOPE_SINGLETON
	 * @see #SCOPE_PROTOTYPE
	 * SCOPE_SINGLETON或者SCOPE_PROTOTYPE两种
	 * Bean的作用域，不考虑web容器，主要两种，单例/原型
	 */
	void setScope(@Nullable String scope);

	/**
	 * Return the name of the current target scope for this bean,
	 * or {@code null} if not known yet.
	 */
	@Nullable
	String getScope();

	/**
	 * Set whether this bean should be lazily initialized.
	 * If {@code false}, the bean will get instantiated on startup by bean
	 * factories that perform eager initialization of singletons.
	 *
	 * @Lazy 是否需要懒加载（默认都是立马加载的）
	 * 是否进行懒加载
	 */
	void setLazyInit(boolean lazyInit);

	/**
	 * Return whether this bean should be lazily initialized, i.e. not
	 * eagerly instantiated on startup. Only applicable to a singleton bean.
	 */
	boolean isLazyInit();

	/**
	 * Set the names of the beans that this bean depends on being initialized.
	 * The bean factory will guarantee that these beans get initialized first.
	 * <p>
	 * 此Bean定义需要依赖的Bean（显然可以有多个）
	 * 是否需要等待指定的bean创建完之后再创建
	 */
	void setDependsOn(@Nullable String... dependsOn);

	/**
	 * Return the bean names that this bean depends on.
	 */
	@Nullable
	String[] getDependsOn();

	/**
	 * Set whether this bean is a candidate for getting autowired into some other bean.
	 * Note that this flag is designed to only affect type-based autowiring.
	 * It does not affect explicit references by name, which will get resolved even
	 * if the specified bean is not marked as an autowire candidate. As a consequence,
	 * autowiring by name will nevertheless inject a bean if the name matches.
	 * <p>
	 * 这个Bean是否允许被自动注入到别的地方去（默认都是被允许的）
	 * 注意：此标志只影响按类型装配，不影响byName的注入方式的~~~~
	 * 是否作为自动注入的候选对象
	 */
	void setAutowireCandidate(boolean autowireCandidate);

	/**
	 * Return whether this bean is a candidate for getting autowired into some other bean.
	 * 这个Bean是否允许被自动注入到别的地方去（默认都是被允许的）
	 * 注意：此标志只影响按类型装配，不影响byName的注入方式的~~~~
	 */
	boolean isAutowireCandidate();

	/**
	 * Set whether this bean is a primary autowire candidate.
	 * If this value is {@code true} for exactly one bean among multiple
	 * matching candidates, it will serve as a tie-breaker.
	 * <p>
	 * 是否是首选的  @Primary
	 * 是否作为主选的bean
	 */
	void setPrimary(boolean primary);

	/**
	 * Return whether this bean is a primary autowire candidate.
	 */
	boolean isPrimary();

	/**
	 * Specify the factory bean to use, if any.
	 * This the name of the bean to call the specified factory method on.
	 *
	 * @see #setFactoryMethodName
	 * <p>
	 * 指定使用的工厂Bean（若存在）的名称~
	 * 创建这个bean的工厂类的名称
	 */
	void setFactoryBeanName(@Nullable String factoryBeanName);

	/**
	 * Return the factory bean name, if any.
	 */
	@Nullable
	String getFactoryBeanName();

	/**
	 * Specify a factory method, if any. This method will be invoked with
	 * constructor arguments, or with no arguments if none are specified.
	 * The method will be invoked on the specified factory bean, if any,
	 * or otherwise as a static method on the local bean class.
	 *
	 * @see #setFactoryBeanName
	 * @see #setBeanClassName
	 * <p>
	 * 指定工厂方法~
	 * 创建这个bean的工厂方法的名称
	 */
	void setFactoryMethodName(@Nullable String factoryMethodName);

	/**
	 * Return a factory method, if any.
	 */
	@Nullable
	String getFactoryMethodName();

	/**
	 * Return the constructor argument values for this bean.
	 * The returned instance can be modified during bean factory post-processing.
	 *
	 * @return the ConstructorArgumentValues object (never {@code null})
	 * <p>
	 * 获取此Bean的构造函数参数值们  ConstructorArgumentValues：持有构造函数们的
	 * 绝大多数情况下是空对象 new ConstructorArgumentValues出来的一个对象
	 * 当我们Scan实例化Bean的时候，可能用到它的非空构造，这里就会有对应的值了，然后后面就会再依赖注入了
	 * <p>
	 * 构造函数的参数
	 */
	ConstructorArgumentValues getConstructorArgumentValues();

	/**
	 * Return if there are constructor argument values defined for this bean.
	 *
	 * @since 5.0.2
	 */
	default boolean hasConstructorArgumentValues() {
		return !getConstructorArgumentValues().isEmpty();
	}

	/**
	 * Return the property values to be applied to a new instance of the bean.
	 * The returned instance can be modified during bean factory post-processing.
	 *
	 * @return the MutablePropertyValues object (never {@code null})
	 * <p>
	 * 获取普通属性集合~~~~
	 * setter方法的参数
	 */
	MutablePropertyValues getPropertyValues();

	/**
	 * Return if there are property values defined for this bean.
	 *
	 * @since 5.0.2
	 */
	default boolean hasPropertyValues() {
		return !getPropertyValues().isEmpty();
	}

	/**
	 * Set the name of the initializer method.
	 *
	 * @since 5.1
	 * 生命周期回调方法，在bean完成属性注入后调用
	 */
	void setInitMethodName(@Nullable String initMethodName);

	/**
	 * Return the name of the initializer method.
	 *
	 * @since 5.1
	 */
	@Nullable
	String getInitMethodName();

	/**
	 * Set the name of the destroy method.
	 *
	 * @since 5.1
	 * 生命周期回调方法，在bean被销毁时调用
	 */
	void setDestroyMethodName(@Nullable String destroyMethodName);

	/**
	 * Return the name of the destroy method.
	 *
	 * @since 5.1
	 */
	@Nullable
	String getDestroyMethodName();

	/**
	 * Set the role hint for this {@code BeanDefinition}. The role hint
	 * provides the frameworks as well as tools an indication of
	 * the role and importance of a particular {@code BeanDefinition}.
	 *
	 * @see #ROLE_APPLICATION 用户定义 int ROLE_APPLICATION = 0;
	 * @see #ROLE_SUPPORT 某些复杂的配置    int ROLE_SUPPORT = 1;
	 * @see #ROLE_INFRASTRUCTURE 完全内部使用   int ROLE_INFRASTRUCTURE = 2;
	 * <p>
	 * 对应上面的role的值
	 * @since 5.1
	 */
	void setRole(int role);

	/**
	 * Get the role hint for this {@code BeanDefinition}. The role hint
	 * provides the frameworks as well as tools an indication of
	 * the role and importance of a particular {@code BeanDefinition}.
	 *
	 * @see #ROLE_APPLICATION
	 * @see #ROLE_SUPPORT
	 * @see #ROLE_INFRASTRUCTURE
	 */
	int getRole();

	/**
	 * Set a human-readable description of this bean definition.
	 *
	 * @since 5.1
	 * <p>
	 * bean的描述，没有什么实际含义
	 */
	void setDescription(@Nullable String description);

	/**
	 * Return a human-readable description of this bean definition.
	 * <p>
	 * 返回该Bean定义来自于的资源的描述（用于在出现错误时显示上下文）
	 */
	@Nullable
	String getDescription();


	// Read-only attributes

	/**
	 * Return a resolvable type for this bean definition,
	 * based on the bean class or other specific metadata.
	 * This is typically fully resolved on a runtime-merged bean definition
	 * but not necessarily on a configuration-time definition instance.
	 *
	 * @return the resolvable type (potentially {@link ResolvableType#NONE})
	 * @see ConfigurableBeanFactory#getMergedBeanDefinition
	 * @since 5.2
	 */
	ResolvableType getResolvableType();

	/**
	 * Return whether this a <b>Singleton</b>, with a single, shared instance
	 * returned on all calls.
	 *
	 * @see #SCOPE_SINGLETON
	 * <p>
	 * 根据scope判断是否是单例
	 */
	boolean isSingleton();

	/**
	 * Return whether this a <b>Prototype</b>, with an independent instance
	 * returned for each call.
	 *
	 * @see #SCOPE_PROTOTYPE
	 * <p>
	 * 根据scope判断是否是原型
	 * @since 3.0
	 */
	boolean isPrototype();

	/**
	 * Return whether this bean is "abstract", that is, not meant to be instantiated.
	 * 跟合并beanDefinition相关，如果是abstract，说明会被作为一个父beanDefinition，不用提供class属性
	 * <p>
	 * 并不是作为父BeanDefinition就一定要设置abstract属性为true，abstract只代表了这个BeanDefinition是否要被Spring进行实例化并被创建对应的Bean，如果为true，代表容器不需要去对其进行实例化
	 * 如果一个BeanDefinition被当作父BeanDefinition使用，并且没有指定其class属性。那么必须要设置其abstract为true
	 * abstract=true一般会跟父BeanDefinition一起使用，因为当我们设置某个BeanDefinition的abstract=true时，一般都是要将其当作BeanDefinition的模板使用，否则这个BeanDefinition也没有意义，除非我们使用其它BeanDefinition来继承它的属性
	 */
	boolean isAbstract();

	/**
	 * Return a description of the resource that this bean definition
	 * came from (for the purpose of showing context in case of errors).
	 * <p>
	 * bean的源描述，没有什么实际含义
	 */
	@Nullable
	String getResourceDescription();

	/**
	 * Return the originating BeanDefinition, or {@code null} if none.
	 * Allows for retrieving the decorated bean definition, if any.
	 * Note that this method returns the immediate originator. Iterate through the
	 * originator chain to find the original BeanDefinition as defined by the user.
	 * <p>
	 * 返回原始BeanDefinition，如果没有则返回@null
	 * cglib代理前的BeanDefinition
	 */
	@Nullable
	BeanDefinition getOriginatingBeanDefinition();

}
