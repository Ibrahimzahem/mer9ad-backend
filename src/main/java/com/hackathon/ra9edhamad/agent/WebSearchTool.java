package com.hackathon.ra9edhamad.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Web search tool — lets agents search the live web for the latest fraud trends,
 * scam patterns, and money-laundering methods. This makes the agents adaptive:
 * they can learn about NEW types of fraud that emerged after their training data.
 *
 * <p>Uses Tavily API (AI-optimized search, free tier 1000 queries/month) if
 * TAVILY_API_KEY is set. Falls back to DuckDuckGo Instant Answer API (free, no key)
 * if no Tavily key is configured.
 *
 * <p>This is the key innovation for the hackathon: the agents don't just rely on
 * static data — they search the web in real-time for emerging threats, like a
 * human fraud analyst would read news feeds.
 */
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String tavilyKey;

    public WebSearchTool() {
        this.tavilyKey = System.getenv("TAVILY_API_KEY");
        if (tavilyKey != null && !tavilyKey.isBlank()) {
            log.info("WebSearchTool active (provider=Tavily)");
        } else {
            log.info("WebSearchTool active (provider=DuckDuckGo fallback, set TAVILY_API_KEY for better results)");
        }
    }

    @Tool("Search the live web for the latest fraud trends, scam patterns, and social engineering tactics. " +
          "Use this to check if the current transfer matches any recently-reported fraud schemes. " +
          "Example queries: 'latest bank transfer scams 2024', 'social engineering fraud Saudi Arabia', " +
          "'new money laundering methods', 'mule account fraud trends'. " +
          "Returns recent articles and summaries about emerging fraud patterns.")
    public String searchLatestFraudTrends(String query) {
        log.info("[WebSearchTool] searchLatestFraudTrends: {}", query);
        return search(query);
    }

    @Tool("Search the live web for the latest money laundering methods and AML trends. " +
          "Use this to check if the current transaction matches any newly-discovered laundering techniques. " +
          "Example queries: 'latest money laundering methods 2024', 'structuring smurfing new patterns', " +
          "'trade-based money laundering trends', 'crypto money laundering AML'. " +
          "Returns recent articles and regulatory alerts about emerging AML threats.")
    public String searchLatestAmlTrends(String query) {
        log.info("[WebSearchTool] searchLatestAmlTrends: {}", query);
        return search(query);
    }

    @Tool("Search the web for specific scam indicators or fraud reports related to a beneficiary name or entity. " +
          "Use this to check if the beneficiary has been reported in any fraud schemes. " +
          "Example: 'Ahmed Trading LLC fraud', 'مؤسسة الفجر scam report'. " +
          "Returns any public fraud reports or warnings about the entity.")
    public String searchEntityFraudReports(String entityName) {
        log.info("[WebSearchTool] searchEntityFraudReports: {}", entityName);
        return search(entityName + " fraud OR scam OR money laundering OR report");
    }

    private String search(String query) {
        if (tavilyKey != null && !tavilyKey.isBlank()) {
            String result = searchTavily(query);
            if (result != null) return result;
        }
        return searchDuckDuckGo(query);
    }

    /**
     * Tavily API — AI-optimized search that returns clean snippets.
     * Free tier: 1000 queries/month. Get key at https://tavily.com
     */
    private String searchTavily(String query) {
        try {
            String body = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                    .put("api_key", tavilyKey)
                    .put("query", query)
                    .put("search_depth", "basic")
                    .put("max_results", 5)
                    .put("include_answer", true));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.tavily.com/search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[WebSearchTool] Tavily returned {}: {}", resp.statusCode(),
                        resp.body().substring(0, Math.min(200, resp.body().length())));
                return null;
            }

            JsonNode root = MAPPER.readTree(resp.body());
            StringBuilder sb = new StringBuilder();

            // Tavily returns a direct answer
            JsonNode answer = root.get("answer");
            if (answer != null && !answer.isNull() && !answer.asText().isBlank()) {
                sb.append("Summary: ").append(answer.asText()).append("\n\n");
            }

            // Results array
            JsonNode results = root.get("results");
            if (results != null && results.isArray()) {
                sb.append("Sources:\n");
                for (int i = 0; i < Math.min(5, results.size()); i++) {
                    JsonNode r = results.get(i);
                    String title = Optional.ofNullable(r.get("title")).map(JsonNode::asText).orElse("Untitled");
                    String snippet = Optional.ofNullable(r.get("content")).map(JsonNode::asText).orElse("");
                    String url = Optional.ofNullable(r.get("url")).map(JsonNode::asText).orElse("");
                    sb.append(String.format("  %d. %s\n     %s\n     Source: %s\n",
                            i + 1, title,
                            truncate(snippet, 200),
                            truncate(url, 80)));
                }
            }

            if (sb.isEmpty()) {
                return "No results found for: " + query;
            }
            return sb.toString();

        } catch (Exception e) {
            log.warn("[WebSearchTool] Tavily search failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * DuckDuckGo Instant Answer API + Wikipedia fallback — free, no key needed.
     */
    private String searchDuckDuckGo(String query) {
        // Try DuckDuckGo first
        String ddgResult = searchDuckDuckGoApi(query);
        if (ddgResult != null && !ddgResult.contains("unavailable") && !ddgResult.contains("No instant results")) {
            return ddgResult;
        }
        // Fallback to Wikipedia search (always works, no auth, no blocks)
        return searchWikipedia(query);
    }

    private String searchDuckDuckGoApi(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URI uri = URI.create("https://api.duckduckgo.com/?q=" + encoded +
                    "&format=json&no_html=1&skip_disambig=1");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Accept", "application/json")
                    .header("User-Agent", "ShieldFraudAgent/1.0")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }

            JsonNode root = MAPPER.readTree(resp.body());
            StringBuilder sb = new StringBuilder();

            String abstractText = Optional.ofNullable(root.get("AbstractText"))
                    .map(JsonNode::asText).orElse("");
            if (!abstractText.isBlank()) {
                sb.append("Summary: ").append(truncate(abstractText, 300)).append("\n");
            }

            JsonNode topics = root.get("RelatedTopics");
            if (topics != null && topics.isArray()) {
                int count = 0;
                for (JsonNode t : topics) {
                    if (count >= 5) break;
                    String text = Optional.ofNullable(t.get("Text")).map(JsonNode::asText).orElse("");
                    if (!text.isBlank()) {
                        sb.append("  • ").append(truncate(text, 150)).append("\n");
                        count++;
                    }
                }
            }

            return sb.isEmpty() ? null : sb.toString();

        } catch (Exception e) {
            log.warn("[WebSearchTool] DuckDuckGo failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Wikipedia REST API — always accessible, no auth, no blocks.
     * Searches for articles related to the query and returns summaries.
     */
    private String searchWikipedia(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // Step 1: Search for relevant articles
            URI searchUri = URI.create("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
                    encoded + "&format=json&srlimit=5&srprop=snippet");

            HttpRequest searchReq = HttpRequest.newBuilder()
                    .uri(searchUri)
                    .header("Accept", "application/json")
                    .header("User-Agent", "ShieldFraudAgent/1.0 (hackathon project)")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> searchResp = http.send(searchReq, HttpResponse.BodyHandlers.ofString());
            if (searchResp.statusCode() != 200) {
                return "Web search temporarily unavailable. Proceed with other tools.";
            }

            JsonNode searchRoot = MAPPER.readTree(searchResp.body());
            JsonNode searchResults = searchRoot.path("query").path("search");

            if (searchResults == null || !searchResults.isArray() || searchResults.isEmpty()) {
                return "No web results found for '" + query + "'. " +
                       "Rely on other tools (mule watchlist, AML classifier, transaction history) for this assessment. " +
                       "For better web search, set TAVILY_API_KEY (free at tavily.com).";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Web search results for '").append(query).append("':\n\n");

            for (int i = 0; i < Math.min(5, searchResults.size()); i++) {
                JsonNode r = searchResults.get(i);
                String title = Optional.ofNullable(r.get("title")).map(JsonNode::asText).orElse("Untitled");
                String snippet = Optional.ofNullable(r.get("snippet")).map(JsonNode::asText).orElse("");
                // Clean HTML tags from snippet
                snippet = snippet.replaceAll("<[^>]+>", "");
                sb.append(String.format("  %d. %s\n     %s\n", i + 1, title, truncate(snippet, 200)));
            }

            // Try to get the first article's extract for more detail
            String firstTitle = Optional.ofNullable(searchResults.get(0).get("title"))
                    .map(JsonNode::asText).orElse(null);
            if (firstTitle != null) {
                String extract = getWikipediaExtract(firstTitle);
                if (extract != null && !extract.isBlank()) {
                    sb.append("\nDetailed summary:\n").append(truncate(extract, 400)).append("\n");
                }
            }

            sb.append("\nNote: Use these results as context. Always corroborate with server-verified tools.");
            return sb.toString();

        } catch (Exception e) {
            log.warn("[WebSearchTool] Wikipedia search failed: {}", e.getMessage());
            return "Web search temporarily unavailable: " + e.getMessage() +
                   ". Proceed with other tools for the assessment.";
        }
    }

    private String getWikipediaExtract(String title) {
        try {
            String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
            URI uri = URI.create("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=true" +
                    "&explaintext=true&titles=" + encoded + "&format=json");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Accept", "application/json")
                    .header("User-Agent", "ShieldFraudAgent/1.0 (hackathon project)")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode pages = root.path("query").path("pages");
            if (pages == null || !pages.isObject()) return null;

            // Get the first page (key is the page ID)
            var fieldIter = pages.fields();
            if (fieldIter.hasNext()) {
                JsonNode firstPage = fieldIter.next().getValue();
                return Optional.ofNullable(firstPage.get("extract")).map(JsonNode::asText).orElse(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
