/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.plugin.mailchimp;

import alfio.model.Ticket;
import alfio.model.TicketReservation;
import alfio.model.plugin.PluginConfigOption;
import alfio.model.system.ComponentType;
import alfio.plugin.PluginDataStorageProvider;
import alfio.plugin.PluginDataStorageProvider.PluginDataStorage;
import alfio.plugin.ReservationConfirmationPlugin;
import alfio.plugin.TicketAssignmentPlugin;
import com.squareup.okhttp.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

public class MailChimpPlugin implements ReservationConfirmationPlugin, TicketAssignmentPlugin {

    private static final String DATA_CENTER = "dataCenter";
    private static final String API_KEY = "apiKey";
    private static final String LIST_ID = "listId";
    private static final String LIST_ADDRESS = "https://%s.api.mailchimp.com/3.0/lists/%s/members/";
    private static final String REQUEST_TEMPLATE = "{ \"email_address\": \"%s\", \"status\": \"subscribed\", \"merge_fields\": { \"FNAME\": \"%s\", \"MC_LANGUAGE\": \"%s\" }}";
    public static final String FAILURE_MSG = "cannot add user {email: %s, name:%s, language: %s} to the list (%s)";
    private final String id = "alfio.mailchimp";
    private final PluginDataStorage pluginDataStorage;
    private final OkHttpClient httpClient = new OkHttpClient();

    public MailChimpPlugin(PluginDataStorageProvider pluginDataStorageProvider) {
        this.pluginDataStorage = pluginDataStorageProvider.getDataStorage(id);
    }


    @Override
    public void onTicketAssignment(Ticket ticket) {
        String email = ticket.getEmail();
        String name = ticket.getFullName();
        String language = ticket.getUserLanguage();
        Optional<String> listAddress = getListAddress(email, name, language);
        Optional<String> apiKey = getApiKey(email, name, language);
        if(listAddress.isPresent() && apiKey.isPresent()) {
            send(listAddress.get(), apiKey.get(), email, name, language);
        }
    }

    @Override
    public void onReservationConfirmation(TicketReservation ticketReservation) {
        String email = ticketReservation.getEmail();
        String name = ticketReservation.getFullName();
        String language = ticketReservation.getUserLanguage();
        Optional<String> listAddress = getListAddress(email, name, language);
        Optional<String> apiKey = getApiKey(email, name, language);
        if(listAddress.isPresent() && apiKey.isPresent()) {
            send(listAddress.get(), apiKey.get(), email, name, language);
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return "Mailchimp newsletter subscriber";
    }

    @Override
    public boolean isEnabled() {
        return pluginDataStorage.getConfigValue(ENABLED_CONF_NAME).map(Boolean::getBoolean).orElse(false);
    }

    @Override
    public Collection<PluginConfigOption> getConfigOptions() {
        return Arrays.asList(new PluginConfigOption(getId(), DATA_CENTER, "", "The MailChimp data center used by your account (e.g. us6)", ComponentType.TEXT),
                new PluginConfigOption(getId(), API_KEY, "", "the Mailchimp API Key", ComponentType.TEXT),
                new PluginConfigOption(getId(), LIST_ID, "", "the list ID", ComponentType.TEXT));
    }

    @Override
    public void install() {
        getConfigOptions().stream().forEach(o -> pluginDataStorage.insertConfigValue(o.getOptionName(), o.getOptionValue(), o.getDescription(), o.getComponentType()));
    }

    private Optional<String> getListAddress(String email, String name, String language) {
        Optional<String> dataCenter = pluginDataStorage.getConfigValue(DATA_CENTER);
        Optional<String> listId = pluginDataStorage.getConfigValue(LIST_ID);
        if(dataCenter.isPresent() && listId.isPresent()) {
            return Optional.of(String.format(LIST_ADDRESS, dataCenter.get(), listId.get()));
        } else {
            pluginDataStorage.registerFailure(String.format(FAILURE_MSG, email, name, language, "check listId and dataCenter"));
        }
        return Optional.empty();
    }

    private Optional<String> getApiKey(String email, String name, String language) {
        Optional<String> apiKey = pluginDataStorage.getConfigValue(API_KEY);
        if(!apiKey.isPresent()) {
            pluginDataStorage.registerFailure(String.format(FAILURE_MSG, email, name, language, "missing API Key"));
        }
        return apiKey;
    }

    private boolean send(String address, String apiKey, String email, String name, String language) {
        Request request = new Request.Builder()
                .url(address)
                .header("Authorization", Credentials.basic("api", apiKey))
                .post(RequestBody.create(MediaType.parse("application/json"), String.format(REQUEST_TEMPLATE, email, name, language)))
                .build();
        try {
            Response response = httpClient.newCall(request).execute();
            if(response.isSuccessful()) {
                pluginDataStorage.registerSuccess(String.format("user %s has been subscribed to list", email));
            } else if(response.code() != 400) {
                pluginDataStorage.registerFailure(String.format(FAILURE_MSG, email, name, language, response.body()));
                return false;
            }
            return true;
        } catch (IOException e) {
            pluginDataStorage.registerFailure(String.format(FAILURE_MSG, email, name, language, e.toString()));
            return false;
        }
    }


}