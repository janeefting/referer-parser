/**
 * Copyright 2012-2013 Snowplow Analytics Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.snowplowanalytics.refererparser;

// Java
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.net.*;
import java.io.*;
import java.util.ArrayList;
import javax.net.ssl.*;

// SnakeYAML
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

// Apache URLEncodedUtils
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

/**
 * Java implementation of <a href="https://github.com/snowplow/referer-parser">Referer Parser</a>
 *
 * @author Alex Dean (@alexatkeplar) <support at snowplowanalytics com>
 */
public class Parser {

  private static final String REFERERS_YAML_PATH = "/referers.yml";
  private Map<String,RefererLookup> referers;

  /**
   * Holds the structure of each referer
   * in our lookup Map.
   */
  private static class RefererLookup {
    public Medium medium;
    public String source;
    public List<String> parameters;

    public RefererLookup(Medium medium, String source, List<String> parameters) {
      this.medium = medium;
      this.source = source;
      this.parameters = parameters;
    }
  }

  /**
   * Construct our Parser object using the
   * bundled referers.yml
   */
  public Parser() throws IOException, CorruptYamlException {
    this(Parser.class.getResourceAsStream(REFERERS_YAML_PATH));
  }

  /**
   * Construct our Parser object using a 
   * InputStream (in YAML format)
   *
   * @param referersYaml The referers YAML
   *        to load into our Parser, in
   *        InputStream format
   */
  public Parser(InputStream referersStream) throws CorruptYamlException {
    referers = loadReferers(referersStream);
  }

  /**
   * Construct our Parser object using a
   * custom resource String
   *
   * @param referersResource The resource pointing
   *        to the referers YAML file to load
   */
  public Parser(String referersResource) throws IOException, CorruptYamlException {
    this(Parser.class.getResourceAsStream(referersResource));
  }

  public Referer parse(URI refererUri, URI pageUri) {
    return parse(refererUri, pageUri.getHost());
  }

  public Referer parse(String refererUri, URI pageUri) throws URISyntaxException {
    return parse(refererUri, pageUri.getHost());
  }

  public Referer parse(String refererUri, String pageHost) throws URISyntaxException {
    if (refererUri == null || refererUri == "") return null;
    final URI uri = new URI(refererUri);
    return parse(uri, pageHost);
  }

  public Referer parse(URI refererUri, String pageHost) {

    // Have to declare up here without `final` due to try/catch scoping
    String scheme;
    String host;
    String path;

    // null unless we have a valid http: or https: URI
    if (refererUri == null) return null;

    try {
      scheme = refererUri.getScheme();
      host = refererUri.getHost();
      path = refererUri.getPath();
    } catch(Exception e) { // Not a valid URL
      return null;
    }

    if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) return null;

    // Internal link if hosts match exactly
    // TODO: would also be nice to:
    // 1. Support a list of other hosts which count as internal
    // 2. Have an algo for stripping subdomains before checking match
    if (host == null) return null; // Not a valid URL
    
    //ArrayList<String> internalReferers = read_https_txt("https://s3-eu-west-1.amazonaws.com/sp-bmi-data-assets/internals.txt");
    
    if (host.equals(pageHost) ||  check_refr_match(host)) return new Referer(Medium.INTERNAL, null, null);


    // Try to lookup our referer. First check with paths, then without.
    // This is the safest way of handling lookups
    RefererLookup referer = lookupReferer(host, path, true);
    if (referer == null) {
      referer = lookupReferer(host, path, false);
    }

    if (referer == null) {
      return new Referer(Medium.UNKNOWN, null, null); // Unknown referer, nothing more to do
    } else {
      // Potentially add a search term
      final String term = (referer.medium == Medium.SEARCH) ? extractSearchTerm(refererUri, referer.parameters) : null;
      return new Referer(referer.medium, referer.source, term);
    }
  }

  /**
   * Recursive function to lookup a host (or partial host)
   * in our referers map.
   *
   * First check the host, then the host+full path, then the host+
   * one-level path.
   *
   * If not found, remove one subdomain-level off the front
   * of the host and try again.
   *
   * @param pageHost The host of the current page
   * @param pagePath The path to the current page
   * @param includePath Whether to include the path in the lookup
   *
   * @return a RefererLookup object populated with the given
   *         referer, or null if not found
   */
  private RefererLookup lookupReferer(String refererHost, String refererPath, Boolean includePath) {

    // Check if domain+full path matches, e.g. for apollo.lv/portal/search/ 
    RefererLookup referer = (includePath) ? referers.get(refererHost + refererPath) : referers.get(refererHost);

    // Check if domain+one-level path matches, e.g. for orange.fr/webmail/fr_FR/read.html (in our YAML it's orange.fr/webmail)
    if (includePath && referer == null) {
      final String[] pathElements = refererPath.split("/");
      if (pathElements.length > 1) {
        referer = referers.get(refererHost + "/" + pathElements[1]);
      }
    }

    if (referer == null) {
      final int idx = refererHost.indexOf('.');
      if (idx == -1) {
        return null; // No "."? Let's quit.
      } else {
        return lookupReferer(refererHost.substring(idx + 1), refererPath, includePath); // Recurse
      }
    } else {
      return referer;
    }
  }

  private String extractSearchTerm(URI uri, List<String> possibleParameters) {

    List<NameValuePair> params;
    try {
      params = URLEncodedUtils.parse(uri, "UTF-8");
    } catch (IllegalArgumentException iae) {
      return null;
    }

    for (NameValuePair pair : params) {
      final String name = pair.getName();
      final String value = pair.getValue();

      if (possibleParameters.contains(name)) {
        return value;
      }
    }
    return null;
  }

  /**
   * Builds the map of hosts to referers from the
   * input YAML file.
   *
   * @param referersYaml An InputStream containing the
   *                     referers database in YAML format.
   *
   * @return a Map where the key is the hostname of each
   *         referer and the value (RefererLookup)
   *         contains all known info about this referer
   */
  private Map<String,RefererLookup> loadReferers(InputStream referersYaml) throws CorruptYamlException {

    Yaml yaml = new Yaml(new SafeConstructor());
    Map<String,Map<String,Map>> rawReferers = (Map<String,Map<String,Map>>) yaml.load(referersYaml);

    // This will store all of our referers
    Map<String,RefererLookup> referers = new HashMap<String,RefererLookup>();

    // Outer loop is all referers under a given medium
    for (Map.Entry<String,Map<String,Map>> mediumReferers : rawReferers.entrySet()) {

      Medium medium = Medium.fromString(mediumReferers.getKey());

      // Inner loop is individual referers
      for (Map.Entry<String,Map> referer : mediumReferers.getValue().entrySet()) {

        String sourceName = referer.getKey();
        Map<String,List<String>> refererMap = referer.getValue();

        // Validate
        List<String> parameters = refererMap.get("parameters");
        if (medium == Medium.SEARCH) {
          if (parameters == null) {
            throw new CorruptYamlException("No parameters found for search referer '" + sourceName + "'");
          }
        } else {
          if (parameters != null) {
            throw new CorruptYamlException("Parameters not supported for non-search referer '" + sourceName + "'");
          }
        }
        List<String> domains = refererMap.get("domains");
        if (domains == null) { 
          throw new CorruptYamlException("No domains found for referer '" + sourceName + "'");
        }

        // Our hash needs referer domain as the
        // key, so let's expand
        for (String domain : domains) {
          if (referers.containsValue(domain)) {
            throw new CorruptYamlException("Duplicate of domain '" + domain + "' found");
          }
          referers.put(domain, new RefererLookup(medium, sourceName, parameters));
        }
      }
    }

    return referers;
  }


   /**
   * Reads the content of a textfile into an arraylist.
   *
   * @param txt_url   A string containing the complete urlpath
   *                  to a web-accessible text file
   *
   * @return An arraylist containing all the strings from the textfile
   */
private ArrayList<String> read_https_txt(String txt_url)
    {
      
      //set the url
      String url_str = txt_url;
      URL https_url = null;
      
      try 
      {
        //define the URL and connect
        https_url = new URL(url_str);
        
        //Cast the type to ensure that it has the correct properties
        HttpsURLConnection con = (HttpsURLConnection)https_url.openConnection();
        
          try
          {
            InputStreamReader ISR = new InputStreamReader(con.getInputStream());
            BufferedReader reader = new BufferedReader(ISR);
             
            String input;
            int index = 0;
            ArrayList<String> result = new ArrayList<>();
             
            
            while ((input = reader.readLine()) != null)
            {
              
              if (!input.startsWith("#") && !input.endsWith("#") && !input.equals(""))
              {
              result.add(input);
              }
              index++;
            }
            
            reader.close();
            return result;
          }
          catch (IOException e) 
          {
            e.printStackTrace();
            return null;
          }
      }
      catch (MalformedURLException e) 
      {
        e.printStackTrace();
        return null;
      }
      catch (IOException e)
      {
        e.printStackTrace();
        return null;
      }
  }

    /**
    * Matches the refr host to strings loaded from a external text file
    * 
    * @param host       A string containing the host url
    *                   of the referer
    *
    * @return boolean   returns true when string is matched
    *                   returns false when string is not matched
    */
    private Boolean check_refr_match(String host)
    {
      //get the contents of the text file
      ArrayList<String> internalReferers = read_https_txt("https://s3-eu-west-1.amazonaws.com/snowplow-bmi-assets/vodafone.nl/internals.txt");
      
      //look through all the partial sub-domains
      //return true when a match is found
      for (int i = 0; i < internalReferers.indexOf("_partial_matches_"); i++) 
      {
        //System.out.println(internalReferers.get(i));
        if (host.endsWith(internalReferers.get(i)))
        {
          return true;
        }
      }
      //look through the entire list of domains
      //return true when a match is found
      if (internalReferers.contains(host))
          {
            return true;
          }
        return false;
    }

}