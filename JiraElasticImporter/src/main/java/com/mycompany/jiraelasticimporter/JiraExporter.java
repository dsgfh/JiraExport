/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mycompany.jiraelasticimporter;

import java.util.ArrayList;
import java.util.Iterator;
import com.mashape.unirest.http.*;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.Console;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author mcoates
 */
public class JiraExporter {

    /**
     * @param args the command line arguments
     * @throws java.lang.Exception
     */
    public static void main(String[] args) throws Exception {

        Console c = System.console();
        if (c == null) {
            System.err.println("No console.");
            System.exit(1);
        }
        String username = c.readLine("Enter your Jira username: ");
        String password = new String(c.readPassword("Enter your password: "));
        final String PROJECT = "IMSP";
        final String BASE_URL = "https://jira.objectconsulting.com.au/rest/api/latest/";
        int start = 0;
        int maxResults = 10;
        int total = 0;
        int fetched = 0;

        HttpResponse<JsonNode> jsonResponse = seachJira(BASE_URL, username, password, start, maxResults);

        JSONObject json = jsonResponse.getBody().getObject();

        total = json.getInt("total");

        while (total >= fetched) {

            JSONArray issues = json.getJSONArray("issues");
            Iterator issuesIterator = issues.iterator();

            while (issuesIterator.hasNext()) {
                JSONObject result = (JSONObject) issuesIterator.next();

                // fetch the individual issue (it has the comments & other details)
                String issueURL = result.getString("self");

                jsonResponse = seachJira(BASE_URL, username, password, start, maxResults);

                result = jsonResponse.getBody().getObject();

                JSONObject fields = result.getJSONObject("fields");

                // remove nulls that elastic cannot handle
                Iterator keys = fields.keys();
                ArrayList toRemove = new ArrayList();

                while (keys.hasNext()) {
                    String key = keys.next().toString();

                    if (fields.isNull(key)) {
                        toRemove.add(key);

                    }
                }

                keys = toRemove.iterator();
                while (keys.hasNext()) {
                    fields.remove(keys.next().toString());
                }

                //pull comment per user stats up
                JSONArray comments = fields.getJSONObject("comment").getJSONArray("comments");
                Map commentMap = new HashMap();
                int fieldCommentCount = 0;
                int fieldCommentLength = 0;
                for (Object comment : comments) {
                    JSONObject jsonComment = (JSONObject) comment;
                    String authorName = jsonComment.getJSONObject("author").getString("name");
                    String body = jsonComment.getString("body");

                    CommentStat commentStat = (CommentStat) commentMap.get(authorName);
                    if (commentStat == null) {
                        commentStat = new CommentStat();
                        commentMap.put(authorName, commentStat);
                    }
                    commentStat.count++;
                    fieldCommentCount++;
                    commentStat.length += body.length();
                    fieldCommentLength += body.length();

                }

                fields.put("commentCount", fieldCommentCount);
                fields.put("commentLength", fieldCommentLength);

                for (Object key : commentMap.keySet()) {
                    JSONObject jsonCommentStats = new JSONObject();
                    jsonCommentStats.append("count", ((CommentStat) commentMap.get(key)).count);
                    jsonCommentStats.append("length", ((CommentStat) commentMap.get(key)).length);
                    fields.getJSONObject("comment").append((String) key, jsonCommentStats);
                }

                fetched++;
                System.out.println("Fetched: " + fetched);
                try {
                    HttpResponse elasticResponse = Unirest.put("http://localhost:9200/jira/" + PROJECT + "/" + result.getString("key")).body(result).asJson();

                    System.out.println("\nElastic Response\n" + elasticResponse.getStatusText() + "\n" + elasticResponse.getBody().toString());

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            start += maxResults;
            jsonResponse = seachJira(BASE_URL, username, password, start, maxResults);

            json = jsonResponse.getBody().getObject();
        }
    }

    private static HttpResponse<JsonNode> seachJira(final String BASE_URL, String username, String password, int start, int maxResults) throws UnirestException {
        HttpResponse<JsonNode> jsonResponse = Unirest.get(BASE_URL + "search")
                .basicAuth(username, password)
                .header("Content-Type", "application/json")
                .queryString("jql", "project = IMSP AND created >= -156w ORDER BY updated DESC")
                .queryString("startAt", start)
                .queryString("maxResults", maxResults)
                .asJson();
        return jsonResponse;
    }
}
