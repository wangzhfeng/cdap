/*
 * Copyright © 2014-2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.common.lang;

import co.cask.cdap.api.app.Application;
import co.cask.cdap.common.internal.guava.ClassPath;
import co.cask.cdap.proto.ProgramType;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.twill.api.ClassAcceptor;
import org.apache.twill.internal.utils.Dependencies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.ws.rs.Path;

/**
 * Helper class to maintain list of resources that are visible to user programs.
 */
public final class ProgramResources {

  private static final Logger LOG = LoggerFactory.getLogger(ProgramResources.class);

  private static final List<String> HADOOP_PACKAGES = ImmutableList.of("org.apache.hadoop.");
  private static final List<String> HBASE_PACKAGES = ImmutableList.of("org.apache.hadoop.hbase.");
  private static final List<String> SPARK_PACKAGES = ImmutableList.of("org.apache.spark.", "scala.");

  private static final Predicate<URI> JAR_ONLY_URI = new Predicate<URI>() {
    @Override
    public boolean apply(URI input) {
      return input.getPath().endsWith(".jar");
    }
  };
  private static final Function<ClassPath.ClassInfo, String> CLASS_INFO_TO_CLASS_NAME =
    new Function<ClassPath.ClassInfo, String>() {
    @Override
    public String apply(ClassPath.ClassInfo input) {
      return input.getName();
    }
  };
  private static final Function<ClassPath.ResourceInfo, String> RESOURCE_INFO_TO_RESOURCE_NAME =
    new Function<ClassPath.ResourceInfo, String>() {
      @Override
      public String apply(ClassPath.ResourceInfo input) {
        return input.getResourceName();
      }
    };


  // Each program type has it's own set of visible resources
  private static Map<ProgramType, Set<String>> visibleResources = Maps.newHashMap();
  // Contains set of resources that are always visible to all program type.
  private static Set<String> baseResources;

  /**
   * Returns a Set of resource names that are visible through to user program.
   *
   * @param classLoader the ClassLoader for finding program type specific resources.
   * @param type program type. If {@code null}, only the base visible resources will be returned.
   */
  public static synchronized Set<String> getVisibleResources(ClassLoader classLoader, @Nullable ProgramType type) {
    if (type == null) {
      return getBaseResources();
    }

    Set<String> resources = visibleResources.get(type);
    if (resources != null) {
      return resources;
    }
    try {
      resources = createVisibleResources(classLoader, type);
    } catch (IOException e) {
      LOG.error("Failed to determine visible resources to user program of type {}", type, e);
      resources = ImmutableSet.of();
    }
    visibleResources.put(type, resources);
    return resources;
  }

  private static Set<String> createVisibleResources(ClassLoader classLoader, ProgramType type) throws IOException {
    Set<String> resources = getBaseResources();

    // Base on the type, add extra resources
    // Current only Spark and Workflow type has extra visible resources
    if (type == ProgramType.SPARK || type == ProgramType.WORKFLOW) {
      resources = getResources(ClassPath.from(classLoader, JAR_ONLY_URI),
                               SPARK_PACKAGES, ImmutableList.<String>of(),
                               RESOURCE_INFO_TO_RESOURCE_NAME, Sets.newHashSet(resources));
    }
    return Collections.unmodifiableSet(resources);
  }

  private static Set<String> getBaseResources() {
    if (baseResources != null) {
      return baseResources;
    }
    try {
      baseResources = createBaseResources();
    } catch (IOException e) {
      LOG.error("Failed to determine base visible resources to user program", e);
      baseResources = ImmutableSet.of();
    }
    return baseResources;
  }

  /**
   * Returns a Set of resources name that are visible through the cdap-api module as well as Hadoop classes.
   * This includes all classes+resources in cdap-api plus all classes+resources that cdap-api
   * depends on (for example, sl4j, guava, gson, etc).
   */
  private static Set<String> createBaseResources() throws IOException {
    // Everything should be traceable in the same ClassLoader of this class, which is the CDAP system ClassLoader
    ClassLoader classLoader = ProgramResources.class.getClassLoader();

    // Gather resources information for cdap-api classes
    ClassPath apiClassPath = getClassPath(classLoader, Application.class);
    // Add everything in cdap-api as visible resources
    Set<String> result = Sets.newHashSet(Iterables.transform(apiClassPath.getResources(),
                                                             RESOURCE_INFO_TO_RESOURCE_NAME));
    // Trace dependencies for cdap-api classes
    findClassDependencies(classLoader, Iterables.transform(apiClassPath.getAllClasses(), CLASS_INFO_TO_CLASS_NAME),
                          result);

    // Gather resources for javax.ws.rs classes. They are not traceable from the api classes.
    Iterables.addAll(result, Iterables.transform(getClassPath(classLoader, Path.class).getResources(),
                                                 RESOURCE_INFO_TO_RESOURCE_NAME));

    // Gather Hadoop classes and resources
    getResources(ClassPath.from(classLoader, JAR_ONLY_URI),
                 HADOOP_PACKAGES, HBASE_PACKAGES, RESOURCE_INFO_TO_RESOURCE_NAME, result);

    return Collections.unmodifiableSet(result);
  }

  /**
   * Finds all resources that are accessible in a given {@link ClassPath} that starts with certain package prefixes.
   * Also includes all non .class file resources in the same base URLs of those classes that are accepted through
   * the package prefixes filtering.
   *
   * Resources information presented in the result collection is transformed by the given result transformation
   * function.
   */
  private static <V, T extends Collection<V>> T getResources(ClassPath classPath,
                                                             Iterable<String> includePackagePrefixes,
                                                             Iterable<String> excludePackagePrefixes,
                                                             Function<ClassPath.ResourceInfo, V> resultTransform,
                                                             final T result) throws IOException {
    Set<URL> resourcesBaseURLs = new HashSet<>();
    // Adds all .class resources that should be included
    // Also record the base URL of those resources
    for (ClassPath.ClassInfo classInfo : classPath.getAllClasses()) {
      boolean include = false;
      for (String prefix : includePackagePrefixes) {
        if (classInfo.getName().startsWith(prefix)) {
          include = true;
          break;
        }
      }
      for (String prefix : excludePackagePrefixes) {
        if (classInfo.getName().startsWith(prefix)) {
          include = false;
          break;
        }
      }

      if (include) {
        result.add(resultTransform.apply(classInfo));
        resourcesBaseURLs.add(classInfo.baseURL());
      }
    }

    // Adds non .class resources that are in the resourceBaseURLs
    for (ClassPath.ResourceInfo resourceInfo : classPath.getResources()) {
      if (resourceInfo instanceof ClassPath.ClassInfo) {
        // We already processed all classes in the loop above
        continue;
      }
      // See if the resource base URL is already accepted through class filtering.
      // If it does, adds the resource name to the collection as well.
      if (resourcesBaseURLs.contains(resourceInfo.baseURL())) {
        result.add(resultTransform.apply(resourceInfo));
      }
    }

    return result;
  }

  /**
   * Returns a {@link ClassPath} instance that represents the classpath that the given class is loaded from the given
   * ClassLoader.
   */
  private static ClassPath getClassPath(ClassLoader classLoader, Class<?> cls) throws IOException {
    String resourceName = cls.getName().replace('.', '/') + ".class";
    URL url = classLoader.getResource(resourceName);
    if (url == null) {
      throw new IOException("Resource not found for " + resourceName);
    }

    try {
      URI classPathURI = getClassPathURL(resourceName, url).toURI();
      return ClassPath.from(classPathURI, classLoader);
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  /**
   * Find the URL of the classpath that contains the given resource.
   */
  private static URL getClassPathURL(String resourceName, URL resourceURL) {
    try {
      if ("file".equals(resourceURL.getProtocol())) {
        String path = resourceURL.getFile();
        // Compute the directory container the class.
        int endIdx = path.length() - resourceName.length();
        if (endIdx > 1) {
          // If it is not the root directory, return the end index to remove the trailing '/'.
          endIdx--;
        }
        return new URL("file", "", -1, path.substring(0, endIdx));
      }
      if ("jar".equals(resourceURL.getProtocol())) {
        String path = resourceURL.getFile();
        return URI.create(path.substring(0, path.indexOf("!/"))).toURL();
      }
    } catch (MalformedURLException e) {
      throw Throwables.propagate(e);
    }
    throw new IllegalStateException("Unsupported class URL: " + resourceURL);
  }

  /**
   * Finds all resource names that the given set of classes depends on.
   *
   * @param classLoader class loader for looking up .class resources
   * @param classes set of class names that need to trace dependencies from
   * @param result collection to store the resulting resource names
   * @param <T> type of the result collection
   * @throws IOException if fails to load class bytecode during tracing
   */
  private static <T extends Collection<String>> T findClassDependencies(final ClassLoader classLoader,
                                                                        Iterable<String> classes,
                                                                        final T result) throws IOException {
    final Set<String> bootstrapClassPaths = getBootstrapClassPaths();
    final Set<URL> classPathSeen = Sets.newHashSet();

    Dependencies.findClassDependencies(classLoader, new ClassAcceptor() {
      @Override
      public boolean accept(String className, URL classUrl, URL classPathUrl) {
        // Ignore bootstrap classes
        if (bootstrapClassPaths.contains(classPathUrl.getFile())) {
          return false;
        }

        // Should ignore classes from SLF4J implementation, otherwise it will includes logback lib, which shouldn't be
        // visible through the program classloader.
        if (className.startsWith("org.slf4j.impl.")) {
          return false;
        }

        if (!classPathSeen.add(classPathUrl)) {
          return true;
        }

        // Add all resources in the given class path
        try {
          ClassPath classPath = ClassPath.from(classPathUrl.toURI(), classLoader);
          for (ClassPath.ResourceInfo resourceInfo : classPath.getResources()) {
            result.add(resourceInfo.getResourceName());
          }
        } catch (Exception e) {
          // If fail to get classes/resources from the classpath, ignore this classpath.
        }
        return true;
      }
    }, classes);

    return result;
  }

  /**
   * Returns a Set containing all bootstrap classpaths as defined in the {@code sun.boot.class.path} property.
   */
  private static Set<String> getBootstrapClassPaths() {
    // Get the bootstrap classpath. This is for exclusion while tracing class dependencies.
    Set<String> bootstrapPaths = Sets.newHashSet();
    for (String classpath : Splitter.on(File.pathSeparatorChar).split(System.getProperty("sun.boot.class.path"))) {
      File file = new File(classpath);
      bootstrapPaths.add(file.getAbsolutePath());
      try {
        bootstrapPaths.add(file.getCanonicalPath());
      } catch (IOException e) {
        // Ignore the exception and proceed.
      }
    }
    return bootstrapPaths;
  }

  private ProgramResources() {
  }
}
