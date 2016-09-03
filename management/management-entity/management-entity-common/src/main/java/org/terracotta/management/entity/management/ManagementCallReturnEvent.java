/*
 * Copyright Terracotta, Inc.
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
package org.terracotta.management.entity.management;

import org.terracotta.management.model.Objects;
import org.terracotta.management.model.call.ContextualReturn;
import org.terracotta.management.model.cluster.ClientIdentifier;

import java.io.Serializable;

/**
 * @author Mathieu Carbou
 */
public class ManagementCallReturnEvent extends ManagementEvent implements Serializable {

  private static final long serialVersionUID = 1;

  private final ContextualReturn<?> contextualReturn;

  public ManagementCallReturnEvent(ClientIdentifier from, String id, ContextualReturn<?> contextualReturn) {
    super(id, from);
    this.contextualReturn = Objects.requireNonNull(contextualReturn);
  }

  public ContextualReturn<?> getContextualReturn() {
    return contextualReturn;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    if (!super.equals(o)) return false;
    ManagementCallReturnEvent that = (ManagementCallReturnEvent) o;
    return contextualReturn.equals(that.contextualReturn);
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + contextualReturn.hashCode();
    return result;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ManagementCallEvent{");
    sb.append("id='").append(getId()).append('\'');
    sb.append(", from=").append(getFrom());
    sb.append(", contextualReturn=").append(contextualReturn);
    sb.append('}');
    return sb.toString();
  }
}