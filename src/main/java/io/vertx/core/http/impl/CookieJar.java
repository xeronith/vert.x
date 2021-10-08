/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core.http.impl;

import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import java.util.*;

/**
 * Vert.x cookie jar implementation. A Cookie Jar is a simple wrapped list that behaves like a Set with one difference.
 * On adding an existing item, the underlying list is updated.
 *
 * This call also provides extra semantics for handling invalidation of cookies, which aren't plain removals as well as
 * quick search utilities to find all matching cookies by name or by unique key.
 *
 * @author <a href="http://pmlopes@gmail.com">Paulo Lopes</a>
 */
public class CookieJar extends AbstractSet<ServerCookie> {

  // keep a shortcut to an empty jar to avoid unnecessary allocations
  private static final CookieJar EMPTY = new CookieJar((List<ServerCookie>) null);

  // the real holder
  private final List<ServerCookie> list;

  public CookieJar() {
    list = new ArrayList<>(4);
  }

  public CookieJar(CharSequence cookieHeader) {
    if (cookieHeader != null) {
      Set<io.netty.handler.codec.http.cookie.Cookie> nettyCookies = ServerCookieDecoder.STRICT.decode(cookieHeader.toString());
      list = new ArrayList<>(nettyCookies.size());
      for (io.netty.handler.codec.http.cookie.Cookie cookie : nettyCookies) {
        list.add(new CookieImpl(cookie));
      }
    } else {
      list = new ArrayList<>(4);
    }
  }

  private CookieJar(List<ServerCookie> list) {
    if (list == null) {
      this.list = Collections.emptyList();
    } else {
      this.list = Collections.unmodifiableList(list);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int size() {
    return list.size();
  }

  /**
   * {@inheritDoc}
   *
   * A Subtle difference here is that matching of cookies is done against the cookie unique identifier, not
   * the {@link #equals(Object)} method.
   */
  @Override
  public boolean contains(Object o) {
    ServerCookie needle = (ServerCookie) o;

    for (ServerCookie cookie : list) {
      if (cookieUniqueIdComparator(cookie, needle.getName(), needle.getDomain(), needle.getPath()) == 0) {
        return true;
      }
    }

    return false;
  }

  @Override
  public Iterator<ServerCookie> iterator() {
    return list.iterator();
  }

  @Override
  public boolean add(ServerCookie cookie) {
    if (cookie == null) {
      throw new NullPointerException("cookie cannot be null");
    }

    for (int i = 0; i < list.size(); i++) {
      int cmp = cookieUniqueIdComparator(list.get(i), cookie.getName(), cookie.getDomain(), cookie.getPath());

      if (cmp > 0) {
        // insert
        list.add(i, cookie);
        return true;
      }
      if (cmp == 0) {
        // replace
        list.set(i, cookie);
        return true;
      }
    }
    // reached the end
    list.add(cookie);
    return true;
  }

  @Override
  public void clear() {
    list.clear();
  }

  /**
   * Follows the {@link Comparator} interface guidelines to compare how the given cookie differs from the unique
   * identifier, the tupple {@code name, domain, path}.
   *
   * @param cookie base cookie
   * @param name name to compare (not null)
   * @param domain maybe nullable domain
   * @param path maybe nullable path
   */
  private static int cookieUniqueIdComparator(ServerCookie cookie, String name, String domain, String path) {
    Objects.requireNonNull(cookie);
    Objects.requireNonNull(name);

    int v = cookie.getName().compareTo(name);
    if (v != 0) {
      return v;
    }

    if (cookie.getPath() == null) {
      if (path != null) {
        return -1;
      }
    } else if (path == null) {
      return 1;
    } else {
      v = cookie.getPath().compareTo(path);
      if (v != 0) {
        return v;
      }
    }

    if (cookie.getDomain() == null) {
      if (domain != null) {
        return -1;
      }
    } else if (domain == null) {
      return 1;
    } else {
      v = cookie.getDomain().compareToIgnoreCase(domain);
      return v;
    }

    return 0;
  }


  public ServerCookie get(String name) {
    for (ServerCookie cookie : list) {
      if (cookie.getName().equals(name)) {
        return cookie;
      }
    }

    return null;
  }

  public CookieJar getAll(String name) {
    List<ServerCookie> subList = null;

    for (ServerCookie cookie : list) {
      if (subList == null) {
        subList = new ArrayList<>(Math.min(4, list.size()));
      }
      if (cookie.getName().equals(name)) {
        subList.add(cookie);
      }
    }

    if (subList != null) {
      return new CookieJar(subList);
    }

    return EMPTY;
  }

  public ServerCookie get(String name, String domain, String path) {
    for (ServerCookie cookie : list) {
      if (cookieUniqueIdComparator(cookie, name, domain, path) == 0) {
        return cookie;
      }
    }

    return null;
  }

  public CookieJar removeOrInvalidateAll(String name, boolean invalidate) {
    Iterator<ServerCookie> it = list.iterator();
    List<ServerCookie> collector = null;

    while (it.hasNext()) {
      ServerCookie cookie = it.next();
      if (cookie.getName().equals(name)) {
        removeOrInvalidateCookie(it, cookie, invalidate);
        if (collector == null) {
          collector = new ArrayList<>(Math.min(4, list.size()));
        }
        collector.add(cookie);
      }
    }

    if (collector != null) {
      return new CookieJar(collector);
    }
    return EMPTY;
  }

  public ServerCookie removeOrInvalidate(String name, String domain, String path, boolean invalidate) {
    Iterator<ServerCookie> it = list.iterator();
    while (it.hasNext()) {
      ServerCookie cookie = it.next();
      if (cookieUniqueIdComparator(cookie, name, domain, path) == 0) {
        removeOrInvalidateCookie(it, cookie, invalidate);
        return cookie;
      }
    }

    return null;
  }

  public ServerCookie removeOrInvalidate(String name, boolean invalidate) {
    Iterator<ServerCookie> it = list.iterator();
    while (it.hasNext()) {
      ServerCookie cookie = it.next();
      if (cookie.getName().equals(name)) {
        removeOrInvalidateCookie(it, cookie, invalidate);
        return cookie;
      }
    }

    return null;
  }

  private static void removeOrInvalidateCookie(Iterator<ServerCookie> it, ServerCookie cookie, boolean invalidate) {
    if (invalidate && cookie.isFromUserAgent()) {
      // in the case the cookie was passed from the User Agent
      // we need to expire it and sent it back to it can be
      // invalidated
      cookie.setMaxAge(0L);
      // void the value for user-agents that still read the cookie
      cookie.setValue("");
    } else {
      // this was a temporary cookie, we can safely remove it
      it.remove();
    }
  }
}
