/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.papermc.paperclip.mixin.classloader;

import java.net.URL;
import java.security.CodeSource;

/**
 * copied from net.fabricmc.loader.impl.launch.knot.KnotClassDelegate
 */
public interface ClassLoaderAccess {
    void addUrlFwd(URL url);
    URL findResourceFwd(String name);

    Package getPackageFwd(String name);
    Package definePackageFwd(String name, String specTitle, String specVersion, String specVendor, String implTitle, String implVersion, String implVendor, URL sealBase) throws IllegalArgumentException;

    Object getClassLoadingLockFwd(String name);
    Class<?> findLoadedClassFwd(String name);
    Class<?> defineClassFwd(String name, byte[] b, int off, int len, CodeSource cs);
    void resolveClassFwd(Class<?> cls);
}
