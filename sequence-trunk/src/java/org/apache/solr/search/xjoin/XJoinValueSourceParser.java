package org.apache.solr.search.xjoin;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queries.function.docvalues.DoubleDocValues;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.search.FunctionQParser;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.search.ValueSourceParser;

/**
 * ValueSourceParser to provide a function for retrieving the value of a field from
 * external process results (for use in sort spec, boost function, etc.)
 */
public class XJoinValueSourceParser extends ValueSourceParser {
  
  // the name of the associated XJoinSearchComponent - could be null
  private String componentName;
  
  // the attribute to examine in external results - could be null
  private String attribute;
  
  // the default value if the results don't have an entry
  private double defaultValue;
  
  /**
   * Initialise from configuration.
   */
  @Override
  @SuppressWarnings("rawtypes")
  public void init(NamedList args) {
    super.init(args);
    
    componentName = (String)args.get(XJoinParameters.INIT_XJOIN_COMPONENT_NAME);
    attribute = (String)args.get(XJoinParameters.INIT_ATTRIBUTE);
    
    Double defaultValue = (Double)args.get(XJoinParameters.INIT_DEFAULT_VALUE);
    if (defaultValue != null) {
      this.defaultValue = defaultValue;
    }
    
    if (componentName == null && attribute == null) {
      throw new RuntimeException("At least one of " + XJoinParameters.INIT_XJOIN_COMPONENT_NAME +
                                 " or " + XJoinParameters.INIT_ATTRIBUTE +
                                 " must be specified");
    }
  }
  
  /**
   * Provide a ValueSource for external process results, which are obtained from the
   * request context (having been placed there by XJoinSearchComponent).
   */
  @Override
  public ValueSource parse(FunctionQParser fqp) throws SyntaxError {
    String componentName = this.componentName != null ? this.componentName : fqp.parseArg();
    String attribute = this.attribute != null ? this.attribute : fqp.parseArg();
    
    XJoinSearchComponent xJoin = (XJoinSearchComponent)fqp.getReq().getCore().getSearchComponent(componentName);
    String joinField = xJoin.getJoinField();
    XJoinResults<?> results = (XJoinResults<?>)fqp.getReq().getContext().get(xJoin.getResultsTag());
    if (results == null) {
      throw new RuntimeException("No xjoin results in request context");
    }
    return new XJoinValueSource(joinField, results, attribute);
  }
  
  /**
   * ValueSource class for external process results.
   */
  public class XJoinValueSource extends ValueSource {

    // the join field
    private String joinField;
    
    // the external process results (generated by XJoinSearchComponent)
    private XJoinResults<?> results;
    
    // the method on external results objects to use as the value
    private String methodName;

    /**
     * Create an ExternalValueSource for the given external process results, for
     * extracting the named attribute.
     */
    public XJoinValueSource(String joinField, XJoinResults<?> results, String attribute) {
      this.joinField = joinField;
      this.results = results;
      this.methodName = NameConverter.getMethodName(attribute);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public FunctionValues getValues(Map context, LeafReaderContext readerContext) throws IOException {
      final BinaryDocValues joinValues = DocValues.getBinary(readerContext.reader(), joinField);

      return new DoubleDocValues(this) {
          
        @Override
        public double doubleVal(int doc) {
          BytesRef joinValue = joinValues.get(doc);
          if (joinValue == null) {
            throw new RuntimeException("No such doc: " + doc);
          }
          Object result = results.getResult(joinValue.utf8ToString());
          if (result == null) {
            return defaultValue;
          }
          try {
            Method method = result.getClass().getMethod(methodName);
            return (Double)method.invoke(result);
          } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
          }
        }

        //FIXME TODO What is the calling convention? Can we cache the BytesRef in exists() for doubleVal()?
        
        @Override
        public boolean exists(int doc) {
          BytesRef joinValue = joinValues.get(doc);
          return joinValue != null;
        }
        
      };
    }
    
    @Override
    public String description() {
      return "$description$";
    }

    @Override
    public boolean equals(Object object) {
      if (! (object instanceof XJoinValueSource)) {
        return false;
      }
      return results.equals(((XJoinValueSource)object).results);
    }

    @Override
    public int hashCode() {
      return results.hashCode();
    }
    
  }

}
