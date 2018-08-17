package plugin;

import com.acme.SampleConfiguration;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.TypeCasting;
import net.bytebuddy.implementation.bytecode.collection.ArrayFactory;
import net.bytebuddy.implementation.bytecode.constant.ClassConstant;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodReturn;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.utility.CompoundList;
import net.bytebuddy.utility.JavaConstant;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinitionCustomizer;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class StaticConfigutationInitializer implements Plugin {

    private final MethodDescription.InDefinedShape registerBean, registerBeanWithSupplier, getBean, lambdaMeta, get;

    public StaticConfigutationInitializer() {
        try {
            registerBean = new MethodDescription.ForLoadedMethod(GenericApplicationContext.class.getMethod("registerBean",
                    Class.class, BeanDefinitionCustomizer[].class));
            registerBeanWithSupplier = new MethodDescription.ForLoadedMethod(GenericApplicationContext.class.getMethod("registerBean",
                    Class.class, Supplier.class, BeanDefinitionCustomizer[].class));
            getBean = new MethodDescription.ForLoadedMethod(BeanFactory.class.getMethod("getBean", Class.class));
            lambdaMeta = new MethodDescription.ForLoadedMethod(LambdaMetafactory.class.getMethod("metafactory",
                    MethodHandles.Lookup.class, String.class, MethodType.class, MethodType.class, MethodHandle.class, MethodType.class));
            get = new MethodDescription.ForLoadedMethod(Supplier.class.getMethod("get"));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder, TypeDescription typeDescription) {
        List<StackManipulation> initializers = new ArrayList<>();
        for (MethodDescription.InDefinedShape methodDescription : typeDescription.getDeclaredMethods().filter(isAnnotatedWith(Bean.class))) {
            List<StackManipulation> stackManipulations = new ArrayList<>();
            for (TypeDescription argumentType : methodDescription.isStatic()
                    ? methodDescription.getParameters().asTypeList().asErasures()
                    : CompoundList.of(typeDescription, methodDescription.getParameters().asTypeList().asErasures())) {
                stackManipulations.add(MethodVariableAccess.REFERENCE.loadFrom(0));
                stackManipulations.add(ClassConstant.of(argumentType));
                stackManipulations.add(MethodInvocation.invoke(getBean));
                stackManipulations.add(TypeCasting.to(argumentType));
            }
            stackManipulations.add(MethodInvocation.invoke(methodDescription));
            stackManipulations.add(MethodReturn.of(methodDescription.getReturnType()));
            builder = builder.defineMethod("init_" + methodDescription.getName(), methodDescription.getReturnType().asErasure(), Visibility.PRIVATE, Ownership.STATIC)
                    .withParameters(BeanFactory.class)
                    .intercept(new Implementation.Simple(new ByteCodeAppender.Simple(stackManipulations)));
            initializers.add(MethodVariableAccess.REFERENCE.loadFrom(1));
            initializers.add(TypeCasting.to(new TypeDescription.ForLoadedType(GenericApplicationContext.class)));
            initializers.add(ClassConstant.of(methodDescription.getReturnType().asErasure()));
            initializers.add(MethodVariableAccess.REFERENCE.loadFrom(1));
            initializers.add(TypeCasting.to(new TypeDescription.ForLoadedType(GenericApplicationContext.class)));
            MethodDescription.InDefinedShape lambda = new MethodDescription.Latent(typeDescription,
                    "init_" + methodDescription.getName(),
                    Modifier.PRIVATE | Modifier.STATIC,
                    Collections.emptyList(),
                    methodDescription.getReturnType().asRawType(),
                    Collections.singletonList(new ParameterDescription.Token(new TypeDescription.ForLoadedType(BeanFactory.class).asGenericType())),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null,
                    null);
            initializers.add(MethodInvocation.invoke(lambdaMeta).dynamic("get",
                    new TypeDescription.ForLoadedType(Supplier.class),
                    Collections.singletonList(new TypeDescription.ForLoadedType(GenericApplicationContext.class)),
                    Arrays.asList(
                            JavaConstant.MethodType.of(get).asConstantPoolValue(),
                            JavaConstant.MethodHandle.of(lambda).asConstantPoolValue(),
                            JavaConstant.MethodType.of(methodDescription.getReturnType().asErasure(), Collections.emptyList()).asConstantPoolValue()
                    )
            ));
            initializers.add(ArrayFactory.forType(new TypeDescription.ForLoadedType(BeanDefinitionCustomizer.class).asGenericType()).withValues(Collections.emptyList()));
            initializers.add(MethodInvocation.invoke(registerBeanWithSupplier));
        }
        initializers.add(0, MethodVariableAccess.REFERENCE.loadFrom(1));
        initializers.add(1, TypeCasting.to(new TypeDescription.ForLoadedType(GenericApplicationContext.class)));
        initializers.add(2, ClassConstant.of(typeDescription));
        initializers.add(3, ArrayFactory.forType(new TypeDescription.ForLoadedType(BeanDefinitionCustomizer.class).asGenericType()).withValues(Collections.emptyList()));
        initializers.add(4, MethodInvocation.invoke(registerBean));
        initializers.add(MethodReturn.VOID);
        return builder.implement(ApplicationContextInitializer.class)
                .method(named("initialize").and(isDeclaredBy(ApplicationContextInitializer.class)))
                .intercept(new Implementation.Simple(new ByteCodeAppender.Simple(initializers)));
    }

    @Override
    public boolean matches(TypeDescription target) {
        return target.getDeclaredAnnotations().isAnnotationPresent(Configuration.class);
    }

    public static void main(String[] args) {
        new StaticConfigutationInitializer().apply(new ByteBuddy().rebase(SampleConfiguration.class), TypeDescription.ForLoadedType.of(SampleConfiguration.class)).make();
    }
}
