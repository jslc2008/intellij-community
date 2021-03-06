// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins;

import com.intellij.AbstractBundle;
import com.intellij.CommonBundle;
import com.intellij.diagnostic.PluginException;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentConfig;
import com.intellij.openapi.components.OldComponentConfig;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionsArea;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.NullableLazyValue;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.util.text.StringUtilRt;
import com.intellij.util.ObjectUtils;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.containers.StringInterner;
import com.intellij.util.xmlb.BeanBinding;
import com.intellij.util.xmlb.JDOMXIncluder;
import com.intellij.util.xmlb.XmlSerializer;
import gnu.trove.THashMap;
import org.jdom.Content;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author mike
 */
public class IdeaPluginDescriptorImpl implements IdeaPluginDescriptor {
  public static final IdeaPluginDescriptorImpl[] EMPTY_ARRAY = new IdeaPluginDescriptorImpl[0];

  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.plugins.PluginDescriptor");

  private final File myPath;
  private final boolean myBundled;

  private final NullableLazyValue<String> myDescription = new NullableLazyValue<String>() {
    @Override
    protected String compute() {
      return computeDescription();
    }
  };
  private String myName;
  private PluginId myId;

  @Nullable
  private String myProductCode;
  @Nullable
  private Date myReleaseDate;
  private int myReleaseVersion;

  private String myResourceBundleBaseName;
  private String myChangeNotes;
  private String myVersion;
  private String myVendor;
  private String myVendorEmail;
  private String myVendorUrl;
  private String myVendorLogoPath;
  private String myCategory;
  private String myUrl;
  private PluginId[] myDependencies = PluginId.EMPTY_ARRAY;
  private PluginId[] myOptionalDependencies = PluginId.EMPTY_ARRAY;
  private Map<PluginId, List<String>> myOptionalConfigs;
  private Map<PluginId, List<IdeaPluginDescriptorImpl>> myOptionalDescriptors;
  @Nullable private List<Element> myActionElements;

  private List<ComponentConfig> myAppComponents;
  private List<ComponentConfig> myProjectComponents;
  private List<ComponentConfig> myModuleComponents;

  private boolean myDeleted;
  private ClassLoader myLoader;
  private HelpSetPath[] myHelpSets;
  @Nullable private MultiMap<String, Element> myExtensions; // extension point name -> list of extension elements
  @Nullable private MultiMap<String, Element> myExtensionsPoints;
  private String myDescriptionChildText;
  private boolean myUseIdeaClassLoader;
  private boolean myUseCoreClassLoader;
  private boolean myAllowBundledUpdate;
  private boolean myEnabled = true;
  private String mySinceBuild;
  private String myUntilBuild;
  private Boolean mySkipped;
  private List<String> myModules;

  public IdeaPluginDescriptorImpl(@NotNull File pluginPath, boolean bundled) {
    myPath = pluginPath;
    myBundled = bundled;
  }

  @SuppressWarnings("HardCodedStringLiteral")
  private static String createDescriptionKey(final PluginId id) {
    return "plugin." + id + ".description";
  }

  @Override
  public File getPath() {
    return myPath;
  }

  /** @deprecated changing a plugin path after loading is not expected (to be removed in IDEA 2019) */
  @Deprecated
  public void setPath(@SuppressWarnings("unused") File path) { }

  public void readExternal(@NotNull Element element, @NotNull URL url, @NotNull JDOMXIncluder.PathResolver pathResolver)
    throws InvalidDataException, MalformedURLException {
    Application application = ApplicationManager.getApplication();
    readExternal(element, url, application != null && application.isUnitTestMode(), pathResolver);
  }

  private void readExternal(@NotNull Element element, @NotNull URL url, boolean ignoreMissingInclude, @NotNull JDOMXIncluder.PathResolver pathResolver)
    throws InvalidDataException, MalformedURLException {
    myAppComponents = Collections.emptyList();
    myProjectComponents = Collections.emptyList();
    myModuleComponents = Collections.emptyList();

    // root element always `!isIncludeElement` and it means that result always is a singleton list
    // (also, plugin xml describes one plugin, this descriptor is not able to represent several plugins)
    if (JDOMUtil.isEmpty(element)) {
      return;
    }

    JDOMXIncluder.resolveNonXIncludeElement(element, url, ignoreMissingInclude, pathResolver);
    readExternal(element);
  }

  public void loadFromFile(@NotNull File file, @Nullable StringInterner stringInterner) throws IOException, JDOMException {
    readExternal(JDOMUtil.load(file, stringInterner), file.toURI().toURL(), JDOMXIncluder.DEFAULT_PATH_RESOLVER);
  }

  // used in upsource
  protected void readExternal(@NotNull Element element) {
    OptimizedPluginBean pluginBean = XmlSerializer.deserialize(element, OptimizedPluginBean.class);
    myUrl = pluginBean.url;

    String idString = StringUtil.nullize(pluginBean.id, true);
    String nameString = StringUtil.nullize(pluginBean.name, true);
    myId = idString != null ? PluginId.getId(idString) : nameString != null ? PluginId.getId(nameString) : null;
    myName = ObjectUtils.chooseNotNull(nameString, idString);

    final ProductDescriptor pd = pluginBean.productDescriptor;
    myProductCode = pd != null? pd.code : null;
    myReleaseDate = parseReleaseDate(pluginBean);
    myReleaseVersion = pd != null? pd.releaseVersion : 0;

    String internalVersionString = pluginBean.formatVersion;
    if (internalVersionString != null) {
      try {
        Integer.parseInt(internalVersionString);
      }
      catch (NumberFormatException e) {
        LOG.error(new PluginException("Invalid value in plugin.xml format version: '" + internalVersionString + "'", e, myId));
      }
    }
    myUseIdeaClassLoader = pluginBean.useIdeaClassLoader;
    myAllowBundledUpdate = pluginBean.allowBundledUpdate;
    if (pluginBean.ideaVersion != null) {
      mySinceBuild = pluginBean.ideaVersion.sinceBuild;
      myUntilBuild = convertExplicitBigNumberInUntilBuildToStar(pluginBean.ideaVersion.untilBuild);
    }

    myResourceBundleBaseName = pluginBean.resourceBundle;

    myDescriptionChildText = pluginBean.description;
    myChangeNotes = pluginBean.changeNotes;
    myVersion = pluginBean.pluginVersion;
    if (myVersion == null) {
      myVersion = PluginManagerCore.getBuildNumber().asStringWithoutProductCode();
    }

    myCategory = pluginBean.category;

    if (pluginBean.vendor != null) {
      myVendor = pluginBean.vendor.name;
      myVendorEmail = pluginBean.vendor.email;
      myVendorUrl = pluginBean.vendor.url;
      myVendorLogoPath = pluginBean.vendor.logo;
    }

    // preserve items order as specified in xml (filterBadPlugins will not fail if module comes first)
    Set<PluginId> dependentPlugins = new LinkedHashSet<>();
    Set<PluginId> optionalDependentPlugins = new LinkedHashSet<>();
    if (pluginBean.dependencies != null) {
      myOptionalConfigs = new THashMap<>();
      for (PluginDependency dependency : pluginBean.dependencies) {
        String text = dependency.pluginId;
        if (!StringUtil.isEmptyOrSpaces(text)) {
          PluginId id = PluginId.getId(text);
          dependentPlugins.add(id);
          if (dependency.optional) {
            optionalDependentPlugins.add(id);
            if (!StringUtil.isEmptyOrSpaces(dependency.configFile)) {
              myOptionalConfigs.computeIfAbsent(id, it -> new SmartList<>()).add(dependency.configFile);
            }
          }
        }
      }
    }

    myDependencies = dependentPlugins.isEmpty() ? PluginId.EMPTY_ARRAY : dependentPlugins.toArray(PluginId.EMPTY_ARRAY);
    myOptionalDependencies = optionalDependentPlugins.isEmpty() ? PluginId.EMPTY_ARRAY : optionalDependentPlugins.toArray(PluginId.EMPTY_ARRAY);

    if (pluginBean.helpSets == null || pluginBean.helpSets.length == 0) {
      myHelpSets = HelpSetPath.EMPTY;
    }
    else {
      myHelpSets = new HelpSetPath[pluginBean.helpSets.length];
      PluginHelpSet[] sets = pluginBean.helpSets;
      for (int i = 0, n = sets.length; i < n; i++) {
        PluginHelpSet pluginHelpSet = sets[i];
        myHelpSets[i] = new HelpSetPath(pluginHelpSet.file, pluginHelpSet.path);
      }
    }

    // we cannot use our new kotlin-aware XmlSerializer, so, will be used different bean cache,
    // but it is not a problem because in any case new XmlSerializer is not used for our core classes (plugin bean, component config and so on).
    Ref<BeanBinding> oldComponentConfigBeanBinding = new Ref<>();

    for (Content content : element.getContent()) {
      if (!(content instanceof Element)) {
        continue;
      }

      Element child = (Element)content;
      switch (child.getName()) {
        case "extensions": {
          if (myExtensions == null) {
            myExtensions = MultiMap.createSmart();
          }
          String ns = child.getAttributeValue("defaultExtensionNs");
          for (Element extension : child.getChildren()) {
            myExtensions.putValue(ExtensionsAreaImpl.extractEPName(extension, ns), extension);
          }
        }
        break;

        case "extensionPoints": {
          if (myExtensionsPoints == null) {
            myExtensionsPoints = MultiMap.createSmart();
          }
          for (Element extensionPoint : child.getChildren()) {
            myExtensionsPoints.putValue(StringUtilRt.notNullize(extensionPoint.getAttributeValue(ExtensionsAreaImpl.ATTRIBUTE_AREA)), extensionPoint);
          }
        }
        break;

        case "actions": {
          if (myActionElements == null) {
            myActionElements = new ArrayList<>(child.getChildren());
          }
          else {
            myActionElements.addAll(child.getChildren());
          }
        }
        break;

        case "module": {
          String moduleName = child.getAttributeValue("value");
          if (moduleName != null) {
            if (myModules == null) {
              myModules = new SmartList<>();
            }
            myModules.add(moduleName);
          }
        }
        break;

        case OptimizedPluginBean.APPLICATION_COMPONENTS: {
          // because of x-pointer, maybe several application-components tag in document
          if (myAppComponents == Collections.<ComponentConfig>emptyList()) {
            myAppComponents = new ArrayList<>();
          }
          readComponents(child, oldComponentConfigBeanBinding, (ArrayList<ComponentConfig>)myAppComponents);
        }
        break;

        case OptimizedPluginBean.PROJECT_COMPONENTS: {
          if (myProjectComponents == Collections.<ComponentConfig>emptyList()) {
            myProjectComponents = new ArrayList<>();
          }
          readComponents(child, oldComponentConfigBeanBinding, (ArrayList<ComponentConfig>)myProjectComponents);
        }
        break;

        case OptimizedPluginBean.MODULE_COMPONENTS: {
          if (myModuleComponents == Collections.<ComponentConfig>emptyList()) {
            myModuleComponents = new ArrayList<>();
          }
          readComponents(child, oldComponentConfigBeanBinding, (ArrayList<ComponentConfig>)myModuleComponents);
        }
        break;
      }
    }
  }

  private static void readComponents(@NotNull Element parent, @NotNull Ref<BeanBinding> oldComponentConfigBean, @NotNull ArrayList<ComponentConfig> result) {
    List<Content> content = parent.getContent();
    int contentSize = content.size();
    if (contentSize == 0) {
      return;
    }

    result.ensureCapacity(result.size() + contentSize);

    for (Content child : content) {
      if (!(child instanceof Element)) {
        continue;
      }

      Element componentElement = ((Element)child);
      if (componentElement.getName().equals("component")) {
        OldComponentConfig componentConfig = new OldComponentConfig();

        BeanBinding beanBinding = oldComponentConfigBean.get();
        if (beanBinding == null) {
          beanBinding = XmlSerializer.getBeanBinding(componentConfig);
          oldComponentConfigBean.set(beanBinding);
        }

        beanBinding.deserializeInto(componentConfig, componentElement);
        result.add(componentConfig);
      }
    }
  }

  @Nullable
  private static Date parseReleaseDate(@NotNull OptimizedPluginBean bean) {
    final ProductDescriptor pd = bean.productDescriptor;
    final String dateStr = pd != null? pd.releaseDate : null;
    if (dateStr != null) {
      try {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).parse(dateStr);
      }
      catch (ParseException e) {
        LOG.info("Error parse release date from plugin descriptor for plugin " + bean.name + " {" + bean.id + "}: " + e.getMessage());
      }
    }
    return null;
  }

  public static final Pattern EXPLICIT_BIG_NUMBER_PATTERN = Pattern.compile("(.*)\\.(9{4,}+|10{4,}+)");

  /**
   * Convert build number like '146.9999' to '146.*' (like plugin repository does) to ensure that plugins which have such values in
   * 'until-build' attribute will be compatible with 146.SNAPSHOT build.
   */
  public static String convertExplicitBigNumberInUntilBuildToStar(@Nullable String build) {
    if (build == null) return null;
    Matcher matcher = EXPLICIT_BIG_NUMBER_PATTERN.matcher(build);
    if (matcher.matches()) {
      return matcher.group(1) + ".*";
    }
    return build;
  }

  public void registerExtensionPoints(@NotNull ExtensionsArea area) {
    if (myExtensionsPoints != null) {
      for (Element element : myExtensionsPoints.get(StringUtil.notNullize(area.getAreaClass()))) {
        area.registerExtensionPoint(this, element);
      }
    }
  }

  // made public for Upsource
  public void registerExtensions(@NotNull ExtensionsArea area, @NotNull String epName) {
    registerExtensions(area, area.getExtensionPoint(epName));
  }

  // made public for Upsource
  public void registerExtensions(@NotNull ExtensionsArea area, @NotNull ExtensionPoint<?> extensionPoint) {
    if (myExtensions == null) {
      return;
    }

    Collection<Element> elements = myExtensions.get(extensionPoint.getName());
    if (elements.isEmpty()) {
      return;
    }

    for (Element element : elements) {
      area.registerExtension(extensionPoint, this, element);
    }
  }

  @Override
  public String getDescription() {
    return myDescription.getValue();
  }

  @Override
  public String getChangeNotes() {
    return myChangeNotes;
  }

  @Override
  public String getName() {
    return myName;
  }

  @Nullable
  @Override
  public String getProductCode() {
    return myProductCode;
  }

  @Nullable
  @Override
  public Date getReleaseDate() {
    return myReleaseDate;
  }

  @Override
  public int getReleaseVersion() {
    return myReleaseVersion;
  }

  @Override
  @NotNull
  public PluginId[] getDependentPluginIds() {
    return myDependencies;
  }

  @Override
  @NotNull
  public PluginId[] getOptionalDependentPluginIds() {
    return myOptionalDependencies;
  }

  @Override
  public String getVendor() {
    return myVendor;
  }

  @Override
  public String getVersion() {
    return myVersion;
  }

  @Override
  public String getResourceBundleBaseName() {
    return myResourceBundleBaseName;
  }

  @Override
  public String getCategory() {
    return myCategory;
  }

  /*
     This setter was explicitly defined to be able to set a category for a
     descriptor outside its loading from the xml file.
     Problem was that most commonly plugin authors do not publish the plugin's
     category in its .xml file so to be consistent in plugins representation
     (e.g. in the Plugins form) we have to set this value outside.
  */
  public void setCategory( String category ){
    myCategory = category;
  }

  @SuppressWarnings("UnusedDeclaration") // Used in Upsource
  @Nullable
  public MultiMap<String, Element> getExtensionsPoints() {
    return myExtensionsPoints;
  }

  @SuppressWarnings("UnusedDeclaration") // Used in Upsource
  @Nullable
  public MultiMap<String, Element> getExtensions() {
    if (myExtensions == null) return null;
    MultiMap<String, Element> result = MultiMap.create();
    result.putAllValues(myExtensions);
    return result;
  }

  @SuppressWarnings("HardCodedStringLiteral")
  @NotNull
  public List<File> getClassPath() {
    if (myPath.isDirectory()) {
      final List<File> result = new ArrayList<>();
      final File classesDir = new File(myPath, "classes");

      if (classesDir.exists()) {
        result.add(classesDir);
      }

      final File[] files = new File(myPath, "lib").listFiles();
      if (files != null && files.length > 0) {
        for (final File f : files) {
          if (f.isFile()) {
            final String name = f.getName();
            if (StringUtil.endsWithIgnoreCase(name, ".jar") || StringUtil.endsWithIgnoreCase(name, ".zip")) {
              result.add(f);
            }
          }
          else {
            result.add(f);
          }
        }
      }

      return result;
    }
    else {
      return Collections.singletonList(myPath);
    }
  }

  @Override
  @Nullable
  public List<Element> getActionsDescriptionElements() {
    return myActionElements;
  }

  @Override
  @NotNull
  public List<ComponentConfig> getAppComponents() {
    return myAppComponents;
  }

  @Override
  @NotNull
  public List<ComponentConfig> getProjectComponents() {
    return myProjectComponents;
  }

  @Override
  @NotNull
  public List<ComponentConfig> getModuleComponents() {
    return myModuleComponents;
  }

  @Override
  public String getVendorEmail() {
    return myVendorEmail;
  }

  @Override
  public String getVendorUrl() {
    return myVendorUrl;
  }

  @Override
  public String getUrl() {
    return myUrl;
  }

  public void setUrl( final String val )
  {
    myUrl = val;
  }

  @Override
  public String toString() {
    return "PluginDescriptor[name='" + myName + "', classpath='" + myPath + "']";
  }

  public boolean isDeleted() {
    return myDeleted;
  }

  public void setDeleted(boolean deleted) {
    myDeleted = deleted;
  }

  public void setLoader(ClassLoader loader) {
    myLoader = loader;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof IdeaPluginDescriptorImpl)) return false;

    final IdeaPluginDescriptorImpl pluginDescriptor = (IdeaPluginDescriptorImpl)o;
    return Objects.equals(myName, pluginDescriptor.myName);
  }

  @Override
  public int hashCode() {
    return myName != null ? myName.hashCode() : 0;
  }

  @Override
  @NotNull
  public HelpSetPath[] getHelpSets() {
    return myHelpSets;
  }

  @Override
  public PluginId getPluginId() {
    return myId;
  }

  /** @deprecated doesn't make sense for installed plugins; use PluginNode#getDownloads (to be removed in IDEA 2019) */
  @Override
  @Deprecated
  public String getDownloads() {
    return null;
  }

  @Override
  public ClassLoader getPluginClassLoader() {
    return myLoader != null ? myLoader : getClass().getClassLoader();
  }

  @Override
  public String getVendorLogoPath() {
    return myVendorLogoPath;
  }

  @Override
  public boolean getUseIdeaClassLoader() {
    return myUseIdeaClassLoader;
  }

  boolean isUseCoreClassLoader() {
    return myUseCoreClassLoader;
  }

  void setUseCoreClassLoader(final boolean useCoreClassLoader) {
    myUseCoreClassLoader = useCoreClassLoader;
  }

  private String computeDescription() {
    ResourceBundle bundle = null;
    if (myResourceBundleBaseName != null) {
      try {
        bundle = AbstractBundle.getResourceBundle(myResourceBundleBaseName, getPluginClassLoader());
      }
      catch (MissingResourceException e) {
        LOG.info("Cannot find plugin " + myId + " resource-bundle: " + myResourceBundleBaseName);
      }
    }

    if (bundle == null) {
      return myDescriptionChildText;
    }

    return CommonBundle.messageOrDefault(bundle, createDescriptionKey(myId), myDescriptionChildText == null ? "" : myDescriptionChildText);
  }

  void insertDependency(@NotNull IdeaPluginDescriptor d) {
    PluginId[] deps = new PluginId[getDependentPluginIds().length + 1];
    deps[0] = d.getPluginId();
    System.arraycopy(myDependencies, 0, deps, 1, deps.length - 1);
    myDependencies = deps;
  }

  @Override
  public boolean isEnabled() {
    return myEnabled;
  }

  @Override
  public void setEnabled(final boolean enabled) {
    myEnabled = enabled;
  }

  @Override
  public String getSinceBuild() {
    return mySinceBuild;
  }

  @Override
  public String getUntilBuild() {
    return myUntilBuild;
  }

  Map<PluginId, List<String>> getOptionalConfigs() {
    return myOptionalConfigs;
  }

  Map<PluginId, List<IdeaPluginDescriptorImpl>> getOptionalDescriptors() {
    return myOptionalDescriptors;
  }

  void setOptionalDescriptors(@NotNull Map<PluginId, List<IdeaPluginDescriptorImpl>> optionalDescriptors) {
    myOptionalDescriptors = optionalDescriptors;
  }

  void mergeOptionalConfig(@NotNull IdeaPluginDescriptorImpl descriptor) {
    if (myExtensions == null) {
      myExtensions = descriptor.myExtensions;
    }
    else if (descriptor.myExtensions != null) {
      myExtensions.putAllValues(descriptor.myExtensions);
    }

    if (myExtensionsPoints == null) {
      myExtensionsPoints = descriptor.myExtensionsPoints;
    }
    else if (descriptor.myExtensionsPoints != null) {
      myExtensionsPoints.putAllValues(descriptor.myExtensionsPoints);
    }

    if (myActionElements == null) {
      myActionElements = descriptor.myActionElements;
    }
    else if (descriptor.myActionElements != null) {
      myActionElements.addAll(descriptor.myActionElements);
    }

    myAppComponents = ContainerUtil.concat(myAppComponents, descriptor.myAppComponents);
    myProjectComponents = ContainerUtil.concat(myProjectComponents, descriptor.myProjectComponents);
    myModuleComponents = ContainerUtil.concat(myModuleComponents, descriptor.myModuleComponents);
  }

  public Boolean getSkipped() {
    return mySkipped;
  }

  public void setSkipped(final Boolean skipped) {
    mySkipped = skipped;
  }

  @Override
  public boolean isBundled() {
    return myBundled;
  }

  @Override
  public boolean allowBundledUpdate() {
    return myAllowBundledUpdate;
  }

  @NotNull
  public List<String> getModules() {
    return ContainerUtil.notNullize(myModules);
  }
}