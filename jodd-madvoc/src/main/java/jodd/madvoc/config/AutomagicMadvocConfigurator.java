// Copyright (c) 2003-present, Jodd Team (http://jodd.org)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
// this list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright
// notice, this list of conditions and the following disclaimer in the
// documentation and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package jodd.madvoc.config;

import jodd.introspector.ClassDescriptor;
import jodd.introspector.ClassIntrospector;
import jodd.introspector.MethodDescriptor;
import jodd.io.findfile.ClassFinder;
import jodd.log.Logger;
import jodd.log.LoggerFactory;
import jodd.madvoc.MadvocException;
import jodd.madvoc.WebApp;
import jodd.madvoc.component.ActionsManager;
import jodd.madvoc.component.MadvocConfig;
import jodd.madvoc.component.MadvocContainer;
import jodd.madvoc.component.MadvocListener;
import jodd.madvoc.component.ResultsManager;
import jodd.madvoc.meta.Action;
import jodd.madvoc.meta.ActionAnnotation;
import jodd.madvoc.meta.MadvocAction;
import jodd.madvoc.meta.MadvocComponent;
import jodd.madvoc.result.ActionResult;
import jodd.petite.meta.PetiteInject;
import jodd.util.ClassLoaderUtil;
import jodd.util.ClassUtil;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Default Madvoc configurator uses auto-magic to configure {@link WebApp}.
 * It searches the class path for all classes which names ends with 'Action' and 'Result'
 * suffixes. Each such class will be loaded and introspected to determine
 * if it represents valid Madvoc entity and then registered into the web application.
 * <p>
 * Action class is scanned for the {@link MadvocAction}. All public methods with {@link Action}
 * are registered as Madvoc actions.
 */
public class AutomagicMadvocConfigurator extends ClassFinder implements MadvocListener.Init, MadvocListener.Start {

	private static final Logger log = LoggerFactory.getLogger(AutomagicMadvocConfigurator.class);

	@PetiteInject
	protected MadvocConfig madvocConfig;

	@PetiteInject
	protected ActionsManager actionsManager;

	@PetiteInject
	protected ResultsManager resultsManager;

	@PetiteInject
	protected MadvocContainer madvocContainer;

	protected String actionClassSuffix;         // default action class suffix, for class path search
	protected String resultClassSuffix;         // default action result class suffix, for class path search
	protected long elapsed;

	protected final byte[] madvocComponentAnnotation;

	protected List<Runnable> webappConfigurations = new ArrayList<>();
	protected List<Runnable> madvocComponents = new ArrayList<>();

	public AutomagicMadvocConfigurator() {
		actionClassSuffix = "Action";
		resultClassSuffix = "Result";
		madvocComponentAnnotation = getTypeSignatureBytes(MadvocComponent.class);
	}

	@Override
	public void init() {
		elapsed = System.currentTimeMillis();

		configureByScanningClassPath(ClassLoaderUtil.getDefaultClasspath());

		madvocComponents.forEach(Runnable::run);

		elapsed = System.currentTimeMillis() - elapsed;
	}

	@Override
	public void start() {
		long now = System.currentTimeMillis();

		webappConfigurations.forEach(Runnable::run);

		elapsed += (System.currentTimeMillis() - now);
		log.info("Madvoc configured in " + elapsed + " ms. Total actions: " + actionsManager.getActionsCount());
	}

	/**
	 * Configures web application from specified classpath. The whole process is done in the following steps:
	 * <ol>
	 * <li>scanning web application classpath</li>
	 * <li>invoking external configurations, if exist</li>
	 * <li>applying defaults</li>
	 * </ol>
	 */
	protected void configureByScanningClassPath(File[] classpath) {
		rulesEntries.smartMode();

		try {
			scanPaths(classpath);
		} catch (Exception ex) {
			throw new MadvocException("Scan classpath error", ex);
		}
	}


	/**
	 * Parses class name that matches madvoc-related names.
	 */
	@Override
	protected void onEntry(EntryData entryData) {
		String entryName = entryData.getName();

		if (entryName.endsWith(actionClassSuffix)) {
			try {
				onActionClass(entryName);
			} catch (Exception ex) {
				log.debug("Invalid Madvoc action, ignoring: " + entryName);
			}
		}
		else if (entryName.endsWith(resultClassSuffix)) {
			try {
				onResultClass(entryName);
			} catch (Exception ex) {
				log.debug("Invalid Madvoc result ignoring: " + entryName);
			}
		}
		else if (isTypeSignatureInUse(entryData, madvocComponentAnnotation)) {
			try {
				onMadvocComponentClass(entryName);
			} catch (Exception ex) {
				log.debug("Invalid Madvoc component ignoring: {}" + entryName);
			}
		}
	}

	// ---------------------------------------------------------------- class check

	/**
	 * Determines if class should be examined for Madvoc annotations.
	 * Array, anonymous, primitive, interfaces and so on should be
	 * ignored. Sometimes, checking may fail due to e.g. <code>NoClassDefFoundError</code>;
	 * we should continue searching anyway.
	 */
	public boolean checkClass(Class clazz) {
		try {
			if (clazz.isAnonymousClass()) {
				return false;
			}
			if (clazz.isArray() || clazz.isEnum()) {
				return false;
			}
			if (clazz.isInterface()) {
				return false;
			}
			if (clazz.isLocalClass()) {
				return false;
			}
			if ((clazz.isMemberClass() ^ Modifier.isStatic(clazz.getModifiers()))) {
				return false;
			}
			if (clazz.isPrimitive()) {
				return false;
			}
			int modifiers = clazz.getModifiers();
			if (Modifier.isAbstract(modifiers)) {
				return false;
			}
			return true;
		} catch (Throwable ignore) {
			return false;
		}
	}

	// ---------------------------------------------------------------- handlers

	/**
	 * Builds action configuration on founded action class.
	 * Action classes are annotated with {@link jodd.madvoc.meta.MadvocAction} annotation.
	 */
	@SuppressWarnings("NonConstantStringShouldBeStringBuffer")
	protected void onActionClass(String className) throws ClassNotFoundException {
		Class<?> actionClass = loadClass(className);

		if (actionClass == null) {
			return;
		}

		if (!checkClass(actionClass)) {
			return; 
		}

		if (actionClass.getAnnotation(MadvocAction.class) == null) {
			return;
		}

		ClassDescriptor cd = ClassIntrospector.get().lookup(actionClass);

		MethodDescriptor[] allMethodDescriptors = cd.getAllMethodDescriptors();
		for (MethodDescriptor methodDescriptor : allMethodDescriptors) {
			if (!methodDescriptor.isPublic()) {
				continue;
			}
			// just public methods
			Method method = methodDescriptor.getMethod();

			boolean hasAnnotation = false;
			for (ActionAnnotation<?> actionAnnotation : madvocConfig.getActionAnnotationInstances()) {
				if (actionAnnotation.hasAnnotation(method)) {
					hasAnnotation = true;
					break;
				}
			}
			if (!hasAnnotation) {
				continue;
			}

			webappConfigurations.add(() -> actionsManager.register(actionClass, method));
		}
	}

	/**
	 * Loads madvoc result from founded {@link jodd.madvoc.result.ActionResult} instance.
	 */
	@SuppressWarnings({"unchecked"})
	protected void onResultClass(String className) throws ClassNotFoundException {
		Class resultClass = loadClass(className);

		if (resultClass == null) {
			return;
		}

		if (!checkClass(resultClass)) {
			return;
		}

		if (ClassUtil.isTypeOf(resultClass, ActionResult.class)) {
			webappConfigurations.add(() -> resultsManager.register(resultClass));
		}
	}

	/**
	 * Registers new Madvoc component.
	 */
	protected void onMadvocComponentClass(String className) throws ClassNotFoundException {
		Class componentClass = loadClass(className);

		if (componentClass == null) {
			return;
		}

		if (!checkClass(componentClass)) {
			return;
		}

		madvocComponents.add(() -> madvocContainer.registerComponent(componentClass));
	}

}
