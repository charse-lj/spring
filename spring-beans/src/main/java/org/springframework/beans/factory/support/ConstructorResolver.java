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

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 * <p>Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	/**
	 * Marker for autowired arguments in a cached argument array, to be later replaced
	 * by a {@linkplain #resolveAutowiredArgument resolved autowired argument}.
	 */
	private static final Object autowiredArgumentMarker = new Object();

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 *
	 * 这个方法作用是获取被包装后的bean，包装后的对象是BeanWrapper对象，这个对象的实现类是BeanWrapperImpl。其中包含被封装后待处理的bean，和设置bean属性的属性编辑器
	 * 了解构造器注入后，工厂注入基本上如出一辙。无论是构造器注入还是工厂方法注入都会面临如下问题：
	 * 1.外部化参数 explicitArgs 覆盖配置参数 bd.args。如果调用 beanFactory#getBean(beanName, args) 时，实例化对象时，只会使用外部参数。
	 * 同时外部化参数只支持精确匹配，即方法参数个数和外部化参数个数必须完全一致。但我们还是推荐使用配置参数，而不推荐使用外部化参数。
	 * 这一思想贯穿了整个参数匹配的过程，阅读源码代码时要首先要有这么一个印象。
	 * 2.（缓存）快速实例化对象。只要执行过一次对象创建，bd 会缓存匹配的构造器和参数。注意，如果使用外部化参数，那么不会使用缓存，因为外部化参数发生变化后，匹配的构造器和参数会改变。
	 * 还有一点，如果是单例，会直接从缓存中命中对象，根本不会再次调用 autowireConstructor 方法创建对象，此时外部化参数不会生效，这可能会让使用者非常困惑，为什么外部化参数就是不生效呢？
	 *     mbd.constructorArgumentLock：同步锁
	 *     mbd.resolvedConstructorOrFactoryMethod：缓存构造器或工厂方法。
	 *     mbd.constructorArgumentsResolved：参数是否解析，boolean 值。
	 *     mbd.resolvedConstructorArguments：缓存解析后的参数。
	 *     mbd.preparedConstructorArguments：缓存参数，使用进还需要进一步解析。
	 * 3.（非缓存）匹配无参构造器。外部化参数和配置参数匹配规则相同：外部化参数和配置参数都为 null，且只有一个无参的构造器。
	 * 4.（非缓存）匹配有参构造器。先计算实际可用的参数，进行参数匹配。如果匹配成功，则计算每个构造器的权重，权重越小优先级越高。如果无法匹配或有多个权重相同的构造器，则抛出异常。
	 * 最后缓存匹配成功的构造器和参数。外部化参数和默认配置参数匹配规则的最大不同是：
	 *     外部化参数匹配：实际参数全部来源于外部化参数。也是就是说，必须精确匹配外部化参数个数，匹配规则非常简单。
	 *     默认配置参数匹配：实际参数除了来源于默认的配置参数外，不足的参数还可以根据其参数类型和参数名称从 Spring IoC 容器中查找（仅当autowiring=true）。也是就是说，构造参数的个数可以大于实际参数个数。当然，如果 Spring IoC 容器中查找不到对应的参数，则该构造器方法肯定无法匹配。
	 *
	 *
	 * 外部化参数：精确匹配参数个数。
	 * 默认配置参数匹配：允许构造器参数大于默认配置参数个数，只要不足的参数能从 Spring IoC容器中解析即可。其实最关键的就是两个参数解析函数：
	 *     resolveConstructorArguments：解析已经有默认参数 bd.constructorArgumentValues。
	 *     createArgumentArray：除了已有的默认参数外，还会从容器中解析（仅当autowiring=true），并将解析的结果包装成 ArgumentsHolder。
	 * 我们重点分析一下 autowiring=true 是什么场景，也就是允许从 Spring IoC 容器中查找依赖自动注入？
	 *     构造器自动注入模式：即 bd.autowireMode=AUTOWIRE_CONSTRUCTOR。我们知道，除了 XML 显示配置构造器自动注入外，一般情况下 bd.autowireMode=NO，也就是返回 false。
	 *     显示的指定构造器：即 chosenCtors!=null，还记得我们之前分析的 createBeanInstance 方法吗？通过后置处理器 ibp#determineCandidateConstructors 可以指定实例化的构造器 ctors。如果装载AutowiredAnnotationBeanPostProcessor 后，会使用构造器自动注入。
	 */
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
			@Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {

		//先实例化一个BeanWrapperImpl类对象
		BeanWrapperImpl bw = new BeanWrapperImpl();
		// initBeanWrapper做了一些事，比如注册解析器、value解析器等等  这是个比较大的概念，后面会有专题再说吧
		this.beanFactory.initBeanWrapper(bw);

		// 1. 使用缓存，快速实例化对象
		Constructor<?> constructorToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		//如果构造参数不为空就直接使用这些参数即可
		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		else {
			// 否则构造函数的入参，交给Spring处理。它会去容器里拿~~~~~
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				//获取已缓存解析的构造函数或工厂方法（resolvedConstructorOrFactoryMethod----用于缓存已解析的构造函数或工厂方法）
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				//如果缓存不为空，并且构造参数已经解析缓存了，(constructorArgumentsResolved为包可见，用于表示构造参数状态是否已经解析)
				// 显然首次进来，都是为null并且没有被解析的
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			// 如果上面没有解析过，显然这里参数就是null了,argsToUse也就还为null Spring下面继续解析
			//可以很清楚的看到外部化参数没有使用缓存，每次都需要重新解析。至于参数的解析 resolvePreparedArguments 会在下方进行分析
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve, true);
			}
		}
		// 非缓存，进行参数匹配
		//如果缓存的构造器不存在，就说明没有bean进行过解析，需要去关联对应的bean的构造器
		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			// 我们的传值chosenCtors 显然不为null，所以此值为true
			Constructor<?>[] candidates = chosenCtors;

			if (candidates == null) {
				Class<?> beanClass = mbd.getBeanClass();
				try {
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			// 2. （非缓存）匹配无参构造器
			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				Constructor<?> uniqueCandidate = candidates[0];
				if (uniqueCandidate.getParameterCount() == 0) {
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			//有参构造器匹配
			//首先，实际参数解析。对于外部化参数而言，只能使用指定的参数。而对于默认配置参数而言，除了配置参数外，还可以根据根据其参数类型和参数名称从 Spring IoC 容器中查找（仅当autowiring=true）。也就是说外部化参数必须精确匹配外部化参数个数，而默认配置参数则构造参数的个数可以大于实际参数个数，只要可以从容器中解析到参数即可。
			//其次，权值计算。权重越小优先级越高。如果无法匹配或有多个权重相同的构造器，则抛出异常。
			//最后，缓存解析的构造器和参数，返回实例化的对象

			//对于外部化参数就不多说了，我们重点说一下绝大多数场景，默认配置参数。首先，解析已有的配置参数；其次，根据构造器参数类型和参数名称从 Spring IoC 容器中解析剩余的参数。如果能成功解析，则命中该构造器
			// Need to resolve the constructor.
			// 我们的传值chosenCtors 显然不为null，所以此值为true
			// autowiring表示是否允许从 Spring IoC 容器查找依赖
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			ConstructorArgumentValues resolvedValues = null;

			int minNrOfArgs;
			//若传入的构造参数不为空，那最小参数长度一塔为准
			// 1.1 外部化参数，不需要解析
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// 1.2 默认配置参数，先解析已有的配置参数
				// 这里相当于要解析出构造函数的参数了
				//解析对应的构造参数然后添加到ConstructorArgumentValues中
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				resolvedValues = new ConstructorArgumentValues();
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			// 这个构造器排序有点意思
			//按照访问方式和数量对构造器进行排序；public>protect>private，在同为public时构造器入参多的排在前面
			// 所以排在第一位的，是public的，参数最多的构造器
			AutowireUtils.sortConstructors(candidates);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			// 记录下，引起歧义的构造器们。就是记录下来，如果存在这种歧义，抛异常的时候用来告诉调用者
			Set<Constructor<?>> ambiguousConstructors = null;
			LinkedList<UnsatisfiedDependencyException> causes = null;

			// 3. （非缓存）匹配有参构造器
			// 开始遍历排序后的构造器了
			for (Constructor<?> candidate : candidates) {
				int parameterCount = candidate.getParameterCount();

				if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					break;
				}
				if (parameterCount < minNrOfArgs) {
					continue;
				}

				ArgumentsHolder argsHolder;
				// 拿到构造器参数的类型们
				Class<?>[] paramTypes = candidate.getParameterTypes();
				// 1.3 默认配置参数，如果配置参数不足，从Spring IoC中继续解析参数。如果能解析则匹配成功
				if (resolvedValues != null) {
					try {
						//兼容JDK6提供的@ConstructorProperties这个注解，如果它标注了参数名，那就以它的名字为准
						//@ConstructorProperties的作用=======》构造函数上的注解，显示该构造函数的参数如何与构造对象的getter方法相对应
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
						// 否则，就自己解析
						if (paramNames == null) {
							// 一般都是Bean工厂默认的DefaultParameterNameDiscoverer 解析出变量名
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						//根据获取到的参数名和已经查到的构造参数和构造参数类型来创建用户创建构造器用的构造参数数组
						//这个数组中包含了原始的参数列表和构造后的参数列表，用来对比用
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						if (causes == null) {
							causes = new LinkedList<>();
						}
						causes.add(ex);
						continue;
					}
				}
				// 1.4 外部化参数，只需要参数个数相等即可。此时resolvedValues=null
				else {
					// Explicit arguments given -> arguments length must match exactly.
					if (parameterCount != explicitArgs.length) {
						continue;
					}
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				//lenientConstructorResolution的值ture与false有什么区别：
				//这个属性默认值是true，在大部分情况下都是使用[宽松模式]，即使多个构造函数的参数数量相同、类型存在父子类、接口实现类关系也能正常创建bean。
				// false表示严格模式。与上面相反
				// typeDiffWeight:返回不同的个数的权重（权重概念?）
				// constructorToUse 表示权重最高的构造器，ambiguousConstructors 表示有多个权重最高的构造器。如果没有匹配的构造器（constructorToUse=null）或有多个权重相等的构造器（ambiguousConstructors!=null）都需要抛出异常
				// 下面我们看一下权重的计算，以 getAssignabilityWeight 为例，权重值越小优先级越高
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				// 根据权重，选择一个最为合适的构造器
				// 权重最高的构造器
				if (typeDiffWeight < minTypeDiffWeight) {
					// 大都进这里来，然后是木有ambiguousConstructors 的
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				// 多个权重最高的构造器
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			// 如果此时还没发现可用的构造器，那这里就开始处理异常吧~
			if (constructorToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}

			//实例化对象
			// 1. 缓存解析后的构造器和参数
			if (explicitArgs == null && argsHolderToUse != null) {
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		// 2. 调用InstantiationStrategy实例化参数
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	/**
	 * 下面步骤都是通用的，用上面得到的构造器（无论是从bean对象中获取的还是spring自己构建的）
	 * 和参数来反射创建bean实例，并放到BeanWrapperImpl对象中然后返回
	 * @param beanName
	 * @param mbd
	 * @param constructorToUse
	 * @param argsToUse
	 * @return
	 */
	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			// 拿到生成Bean实例化策略，默认值为CglibSubclassingInstantiationStrategy  用CGLIB生成子类的方式
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse),
						this.beanFactory.getAccessControlContext());
			}
			else {
				// 主要就是调用了策略器的instantiate，对Bean进行了最终的实例化
				// 此方法为重载方法，此处因为不需要代理，所以执行的直接是SimpleInstantiationStrategy#instantiate
				// 到此处，有一个HelloServiceImpl正式创建   然后继续到doCreateBean方法去吧
				return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via constructor failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (isParamMismatch(uniqueCandidate, candidate)) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged((PrivilegedAction<Method[]>) () ->
					(mbd.isNonPublicAccessAllowed() ?
						ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods()));
		}
		else {
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * 一般表示注解驱动（ @Bean 注册），通过工厂方法实例化。委托给 ConstructorResolver#instantiateUsingFactoryMethod。
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {

		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		Object factoryBean;
		Class<?> factoryClass;
		boolean isStatic;

		//TODO ?? 是否为静态工厂方法如何区分?
		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			//工厂bean名不能和需要实例化的beanName同名
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				//需要实例化的单例对象已经存在
				throw new ImplicitlyAppearedSingletonException();
			}
			factoryClass = factoryBean.getClass();
			isStatic = false;
		}
		else {
			// It's a static factory method on the bean class.
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			factoryBean = null;
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}

		Method factoryMethodToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve, true);
			}
		}

		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			factoryClass = ClassUtils.getUserClass(factoryClass);

			List<Method> candidates = null;
			if (mbd.isFactoryMethodUnique) {
				if (factoryMethodToUse == null) {
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				if (factoryMethodToUse != null) {
					candidates = Collections.singletonList(factoryMethodToUse);
				}
			}
			//遍历筛选符合条件的工厂方法
			if (candidates == null) {
				candidates = new ArrayList<>();
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				for (Method candidate : rawCandidates) {
					if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
						candidates.add(candidate);
					}
				}
			}

			//候选工厂方法唯一,外置参数为null,mbd无构造参数
			if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				Method uniqueCandidate = candidates.get(0);
				if (uniqueCandidate.getParameterCount() == 0) {
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					synchronized (mbd.constructorArgumentLock) {
						//缓存
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			// explicitly skip immutable singletonList
			if (candidates.size() > 1) {
				candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
			}

			ConstructorArgumentValues resolvedValues = null;
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Method> ambiguousFactoryMethods = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				if (mbd.hasConstructorArgumentValues()) {
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					//解析内置参数
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				}
				else {
					minNrOfArgs = 0;
				}
			}

			LinkedList<UnsatisfiedDependencyException> causes = null;

			//遍历符合条件的工厂方法
			for (Method candidate : candidates) {
				//工厂方法参数个数
				int parameterCount = candidate.getParameterCount();

				if (parameterCount >= minNrOfArgs) {
					ArgumentsHolder argsHolder;

					Class<?>[] paramTypes = candidate.getParameterTypes();
					if (explicitArgs != null) {
						//外置参数必须和工厂方法需要的参数个数相同 --> 精确匹配
						// Explicit arguments given -> arguments length must match exactly.
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							//获取工厂方法参数名
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							//
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							if (causes == null) {
								causes = new LinkedList<>();
							}
							causes.add(ex);
							continue;
						}
					}

					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			if (factoryMethodToUse == null || argsToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				if (explicitArgs != null) {
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				else if (resolvedValues != null) {
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found: " +
						(mbd.getFactoryBeanName() != null ?
							"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() +
						"': needs to have a non-void return type!");
			}
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	private Object instantiate(String beanName, RootBeanDefinition mbd,
			@Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						this.beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args),
						this.beanFactory.getAccessControlContext());
			}
			else {
				return this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via factory method failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * <p>This method is also used for handling invocations of static factory methods.
	 * 解析默认的配置参数 mbd.constructorArgumentValues
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		int minNrOfArgs = cargs.getArgumentCount();

		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			int index = entry.getKey();
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			if (index + 1 > minNrOfArgs) {
				minNrOfArgs = index + 1;
			}
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			if (valueHolder.isConverted()) {
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			else {
				// 核心代码就这么一句，将参数解析全部委托给valueResolver
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			if (valueHolder.isConverted()) {
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 * @param resolvedValues 表示bd.constructorArgumentValues中已经解析的参数
	 * @param paramTypes 表示当前的构造器或方法的参数类型列表
	 * @param paramNames 方法的参数名称
	 * @param executable 方法或构造器
	 * @param autowiring 是否采用构造器自动注入，如果是则会从Spring IoC容器中查找参数
	 * @param fallback 如果已经有多个构造器或方法匹配，则fallback=false
	 * 先到 resolveConstructorArguments 方法已经解析过的参数中查找参数，如果查找到就使用配置的参数。当然如果查找不到，这时就判断是否允许自行注入，如果允许，则从 Spring IoC 容器中查找依赖，即调用 beanFactory#resolveDependency 方法
	 * 除了已有的默认参数外，还会从容器中解析（仅当 autowiring=true），并将解析的结果包装成 ArgumentsHolder。
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);

		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			Class<?> paramType = paramTypes[paramIndex];
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			// 1. 从默认的配置参数中查找参数
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			if (resolvedValues != null) {
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					// 1.1 查找已经有参数，可以根据参数的索引或名称查找
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			// 2. 已经从配置该参数，这部分代码省略
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				usedValueHolders.add(valueHolder);
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				if (valueHolder.isConverted()) {
					convertedValue = valueHolder.getConvertedValue();
					args.preparedArguments[paramIndex] = convertedValue;
				}
				// 3. 没有配置该参数，尝试从Spring IoC容器中查找
				else {
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
										ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
										"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					Object sourceHolder = valueHolder.getSource();
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
						Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
						args.resolveNecessary = true;
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			}
			else {
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				try {
					// 3.1 核心代码，调用 beanFactory#resolveDependency
					Object autowiredArgument = resolveAutowiredArgument(
							methodParam, beanName, autowiredBeanNames, converter, fallback);
					args.rawArguments[paramIndex] = autowiredArgument;
					// 3.2 自动注入标记位
					args.arguments[paramIndex] = autowiredArgument;
					args.preparedArguments[paramIndex] = autowiredArgumentMarker;
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}

		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + (executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 *
	 * resolvePreparedArguments 方法是对 mbd.preparedConstructorArguments 中还需要解析的参数调用 BeanDefinitionValueResolver#resolveValueIfNecessary 进行解析
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			Executable executable, Object[] argsToResolve, boolean fallback) {

		//属性转换器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		//方法或构造函数的参数类型
		Class<?>[] paramTypes = executable.getParameterTypes();

		//被解析的参数对象
		Object[] resolvedArgs = new Object[argsToResolve.length];
		//遍历
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			//遍历中的参数对象
			Object argValue = argsToResolve[argIndex];
			//同位置的方法参数类型
			MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
			// createArgumentArray 方法设置的自动注入标记
			if (argValue == autowiredArgumentMarker) {
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter, fallback);
			}
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			}
			else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			Class<?> paramType = paramTypes[argIndex];
			try {
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			}
			catch (TypeMismatchException ex) {
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
						"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
						"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		return resolvedArgs;
	}

	protected Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 */
	@Nullable
	protected Object resolveAutowiredArgument(MethodParameter param, String beanName,
			@Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {

		Class<?> paramType = param.getParameterType();
		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + param);
			}
			return injectionPoint;
		}
		try {
			return this.beanFactory.resolveDependency(
					new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
		}
		catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		}
		catch (NoSuchBeanDefinitionException ex) {
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				if (paramType.isArray()) {
					return Array.newInstance(paramType.getComponentType(), 0);
				}
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				}
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			throw ex;
		}
	}

	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		/**
		 * 原始的参数，这个主要是用来计算构造器的权重。
		 */
		public final Object[] rawArguments;

		/**
		 * 已经解析后的参数，不需要再进行解析，直接可以注入。
		 */
		public final Object[] arguments;

		/**
		 * 已部分解析后的参数，这个主要是用来解析注入的实际参数。
		 */
		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			// 1. arguments表示解析后的参数
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			// 2. arguments表示解析前的参数，如配置参数
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		/**
		 * 在构造器实例化对象开始前，第一步就是从 bd 缓存中快速实例化对象。其中无参构造器已经在匹配无参构造器时缓存，此时需要缓存有参构造器匹配的变量。
		 * @param mbd .
		 * @param constructorOrFactoryMethod .
		 */
		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java 6's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}

}
