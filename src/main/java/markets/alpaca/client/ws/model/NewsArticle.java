package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * A real-time news article event ({@code T: "n"}).
 *
 * @param id unique news article ID
 * @param headline headline or title of the article
 * @param summary summary text (may be the first sentence of the content)
 * @param author original author
 * @param createdAt publication timestamp (RFC-3339)
 * @param updatedAt last update timestamp (RFC-3339)
 * @param url URL of the article (may be null)
 * @param content full article content, may contain HTML (may be null)
 * @param symbols related stock or crypto symbols
 * @param source news source identifier (e.g. {@code "benzinga"})
 */
public record NewsArticle(
    @SerializedName("id") long id,
    @SerializedName("headline") String headline,
    @SerializedName("summary") String summary,
    @SerializedName("author") String author,
    @SerializedName("created_at") String createdAt,
    @SerializedName("updated_at") String updatedAt,
    @SerializedName("url") String url,
    @SerializedName("content") String content,
    @SerializedName("symbols") List<String> symbols,
    @SerializedName("source") String source) {}
