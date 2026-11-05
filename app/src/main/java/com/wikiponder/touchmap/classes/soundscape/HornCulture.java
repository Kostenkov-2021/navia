package com.wikiponder.touchmap.classes.soundscape;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * How much drivers honk at a point: a country's driving habits times how crowded the city is (people
 * per area). Two hand-made city tables, by centre and rough radius in km; every other place counts as
 * a small town. These are judgement calls for a sound effect, not measurements.
 */
final class HornCulture {
	
	private static final Set<String> HONKING = new HashSet<>(Arrays.asList(
			"IN", "BD", "PK", "NP", "LK", "MM", "KH", "LA", "VN", "ID", "PH", "EG", "IR", "IQ", "SY", "LB", "JO",
			"YE", "AF", "NG", "GH", "KE", "TZ", "UG", "ET", "SN", "CI", "CM", "CD", "MA", "DZ", "TN", "HT"));
	private static final Set<String> FAIRLY_HONKING = new HashSet<>(Arrays.asList(
			"TR", "TH", "CN", "MY", "SA", "AE", "KW", "MX", "BR", "CO", "PE", "VE", "EC", "BO", "AR", "RU", "UA",
			"ZA", "GR", "IT", "RO", "BG", "RS", "GE", "AM", "AZ", "UZ", "KZ"));
	private static final Set<String> QUIET = new HashSet<>(Arrays.asList(
			"DE", "NL", "BE", "AT", "CH", "LU", "SE", "NO", "DK", "FI", "IS", "JP", "NZ", "SI", "EE", "LV", "LT"));
	private static final Set<String> FAIRLY_QUIET = new HashSet<>(Arrays.asList(
			"US", "CA", "GB", "IE", "FR", "AU", "KR", "SG", "CZ", "SK", "PL", "HU", "TW", "IL"));

	private static final float BUSY_CITY = 1.6f, MEDIUM_CITY = 1f, SMALL_TOWN = 0.5f;

	/** Crowded cities: many people on little land. {name, lat, lon, radius km} */
	private static final Object[][] BUSY = {
			{"Dhaka", 23.78, 90.40, 22}, {"Chittagong", 22.34, 91.82, 15}, {"Mumbai", 19.08, 72.88, 35},
			{"Delhi", 28.65, 77.20, 40}, {"Kolkata", 22.57, 88.36, 25}, {"Chennai", 13.07, 80.25, 25},
			{"Bangalore", 12.97, 77.59, 25}, {"Hyderabad", 17.39, 78.48, 25}, {"Ahmedabad", 23.03, 72.58, 20},
			{"Pune", 18.52, 73.86, 20}, {"Surat", 21.18, 72.83, 15}, {"Jaipur", 26.91, 75.79, 15},
			{"Lucknow", 26.85, 80.95, 15}, {"Kanpur", 26.45, 80.33, 15}, {"Patna", 25.60, 85.12, 12},
			{"Varanasi", 25.32, 83.01, 10}, {"Karachi", 24.88, 67.03, 30}, {"Lahore", 31.55, 74.34, 25},
			{"Faisalabad", 31.42, 73.08, 15}, {"Rawalpindi", 33.60, 73.05, 15}, {"Kabul", 34.53, 69.17, 15},
			{"Kathmandu", 27.71, 85.32, 10}, {"Colombo", 6.93, 79.86, 12}, {"Yangon", 16.84, 96.17, 18},
			{"Tehran", 35.70, 51.40, 28}, {"Mashhad", 36.30, 59.60, 15}, {"Isfahan", 32.65, 51.67, 14},
			{"Shiraz", 29.61, 52.53, 12}, {"Tabriz", 38.08, 46.29, 12}, {"Baghdad", 33.31, 44.37, 22},
			{"Cairo", 30.05, 31.25, 30}, {"Alexandria", 31.20, 29.92, 18}, {"Khartoum", 15.55, 32.53, 18},
			{"Casablanca", 33.57, -7.59, 15}, {"Algiers", 36.75, 3.06, 15}, {"Lagos", 6.52, 3.38, 28},
			{"Kano", 12.00, 8.52, 12}, {"Ibadan", 7.38, 3.90, 12}, {"Accra", 5.60, -0.19, 15},
			{"Abidjan", 5.33, -4.03, 18}, {"Dakar", 14.72, -17.47, 12}, {"Kinshasa", -4.33, 15.31, 22},
			{"Nairobi", -1.29, 36.82, 15}, {"Addis Ababa", 9.01, 38.75, 15}, {"Kampala", 0.32, 32.58, 12},
			{"Dar es Salaam", -6.80, 39.28, 18}, {"Istanbul", 41.02, 28.97, 35}, {"Jakarta", -6.20, 106.85, 35},
			{"Surabaya", -7.26, 112.75, 15}, {"Bandung", -6.91, 107.61, 12}, {"Manila", 14.60, 120.98, 28},
			{"Ho Chi Minh City", 10.78, 106.70, 25}, {"Hanoi", 21.03, 105.85, 20}, {"Bangkok", 13.75, 100.50, 30},
			{"Hong Kong", 22.32, 114.17, 18}, {"Shanghai", 31.23, 121.47, 35}, {"Beijing", 39.91, 116.40, 30},
			{"Guangzhou", 23.13, 113.26, 28}, {"Shenzhen", 22.54, 114.06, 25}, {"Chongqing", 29.56, 106.55, 22},
			{"Wuhan", 30.59, 114.31, 20}, {"Chengdu", 30.66, 104.07, 20}, {"Seoul", 37.56, 126.98, 25},
			{"Tokyo", 35.69, 139.75, 35}, {"Osaka", 34.69, 135.50, 22}, {"Mexico City", 19.43, -99.13, 30},
			{"Sao Paulo", -23.55, -46.63, 32}, {"Rio de Janeiro", -22.91, -43.20, 25}, {"Bogota", 4.65, -74.08, 18},
			{"Lima", -12.05, -77.04, 25}, {"Buenos Aires", -34.60, -58.40, 25}, {"Port-au-Prince", 18.55, -72.34, 10},
			{"New York", 40.73, -73.97, 22}, {"Paris", 48.86, 2.35, 14}, {"Moscow", 55.75, 37.62, 25},
			{"Naples", 40.85, 14.27, 12}, {"Athens", 37.98, 23.73, 14},
	};

	/** Ordinary big cities: room to breathe, but real city traffic. {name, lat, lon, radius km} */
	private static final Object[][] MEDIUM = {
			{"Amsterdam", 52.37, 4.89, 12}, {"Rotterdam", 51.92, 4.48, 12}, {"The Hague", 52.08, 4.31, 9},
			{"Utrecht", 52.09, 5.12, 8}, {"Brussels", 50.85, 4.35, 12}, {"Antwerp", 51.22, 4.40, 10},
			{"Berlin", 52.52, 13.40, 22}, {"Hamburg", 53.55, 9.99, 18}, {"Munich", 48.14, 11.58, 14},
			{"Cologne", 50.94, 6.96, 12}, {"Frankfurt", 50.11, 8.68, 12}, {"Stuttgart", 48.78, 9.18, 10},
			{"Dusseldorf", 51.23, 6.78, 10}, {"Vienna", 48.21, 16.37, 15}, {"Zurich", 47.37, 8.54, 9},
			{"Copenhagen", 55.68, 12.57, 12}, {"Stockholm", 59.33, 18.07, 14}, {"Oslo", 59.91, 10.75, 10},
			{"Helsinki", 60.17, 24.94, 11}, {"London", 51.51, -0.12, 25}, {"Birmingham", 52.48, -1.89, 12},
			{"Manchester", 53.48, -2.24, 12}, {"Glasgow", 55.86, -4.25, 10}, {"Dublin", 53.35, -6.26, 11},
			{"Lyon", 45.76, 4.84, 10}, {"Marseille", 43.30, 5.37, 12}, {"Madrid", 40.42, -3.70, 16},
			{"Barcelona", 41.39, 2.17, 12}, {"Valencia", 39.47, -0.38, 8}, {"Lisbon", 38.72, -9.14, 11},
			{"Porto", 41.15, -8.61, 8}, {"Rome", 41.90, 12.50, 15}, {"Milan", 45.46, 9.19, 12},
			{"Turin", 45.07, 7.68, 9}, {"Warsaw", 52.23, 21.01, 14}, {"Krakow", 50.06, 19.94, 9},
			{"Prague", 50.08, 14.43, 12}, {"Budapest", 47.50, 19.05, 14}, {"Bucharest", 44.43, 26.10, 12},
			{"Sofia", 42.70, 23.32, 10}, {"Belgrade", 44.82, 20.46, 10}, {"Kyiv", 50.45, 30.52, 16},
			{"Saint Petersburg", 59.94, 30.31, 18}, {"Minsk", 53.90, 27.56, 11}, {"Ankara", 39.93, 32.86, 15},
			{"Izmir", 38.42, 27.14, 12}, {"Bursa", 40.19, 29.06, 9}, {"Tbilisi", 41.72, 44.79, 9},
			{"Yerevan", 40.18, 44.51, 8}, {"Baku", 40.41, 49.87, 12}, {"Tashkent", 41.31, 69.28, 14},
			{"Almaty", 43.24, 76.89, 12}, {"Riyadh", 24.71, 46.68, 22}, {"Jeddah", 21.54, 39.17, 16},
			{"Dubai", 25.20, 55.27, 20}, {"Abu Dhabi", 24.45, 54.38, 12}, {"Doha", 25.29, 51.53, 12},
			{"Kuwait City", 29.37, 47.98, 14}, {"Amman", 31.95, 35.93, 12}, {"Beirut", 33.89, 35.50, 8},
			{"Damascus", 33.51, 36.29, 10}, {"Tel Aviv", 32.08, 34.78, 10}, {"Jerusalem", 31.78, 35.22, 8},
			{"Karaj", 35.83, 50.99, 9}, {"Qom", 34.64, 50.88, 8}, {"Ahvaz", 31.32, 48.67, 9},
			{"Kermanshah", 34.32, 47.07, 7}, {"Tunis", 36.81, 10.18, 10}, {"Rabat", 34.02, -6.84, 8},
			{"Johannesburg", -26.20, 28.05, 18}, {"Cape Town", -33.92, 18.42, 16}, {"Durban", -29.86, 31.03, 11},
			{"Abuja", 9.06, 7.49, 12}, {"Luanda", -8.84, 13.23, 15}, {"Singapore", 1.35, 103.82, 18},
			{"Kuala Lumpur", 3.14, 101.69, 16}, {"Taipei", 25.04, 121.56, 14}, {"Busan", 35.18, 129.08, 12},
			{"Nagoya", 35.17, 136.91, 12}, {"Fukuoka", 33.59, 130.40, 9}, {"Sapporo", 43.06, 141.35, 9},
			{"Kyoto", 35.01, 135.77, 8}, {"Yokohama", 35.44, 139.64, 11}, {"Nanjing", 32.06, 118.78, 14},
			{"Hangzhou", 30.27, 120.15, 14}, {"Xi'an", 34.27, 108.95, 12}, {"Tianjin", 39.13, 117.20, 14},
			{"Sydney", -33.87, 151.21, 22}, {"Melbourne", -37.81, 144.96, 20}, {"Brisbane", -27.47, 153.03, 13},
			{"Perth", -31.95, 115.86, 13}, {"Auckland", -36.85, 174.76, 12}, {"Toronto", 43.70, -79.42, 20},
			{"Montreal", 45.50, -73.57, 14}, {"Vancouver", 49.26, -123.12, 11}, {"Calgary", 51.05, -114.07, 12},
			{"Los Angeles", 34.05, -118.25, 28}, {"Chicago", 41.88, -87.65, 20}, {"Houston", 29.76, -95.37, 22},
			{"Philadelphia", 39.95, -75.16, 12}, {"Phoenix", 33.45, -112.07, 18}, {"San Francisco", 37.77, -122.42, 9},
			{"Seattle", 47.61, -122.33, 11}, {"Boston", 42.36, -71.06, 10}, {"Washington", 38.90, -77.04, 12},
			{"Atlanta", 33.75, -84.39, 12}, {"Miami", 25.78, -80.21, 13}, {"Dallas", 32.78, -96.80, 15},
			{"Guadalajara", 20.67, -103.35, 13}, {"Monterrey", 25.67, -100.31, 13}, {"Havana", 23.13, -82.38, 10},
			{"Santo Domingo", 18.49, -69.93, 10}, {"Caracas", 10.49, -66.88, 11}, {"Medellin", 6.25, -75.56, 10},
			{"Quito", -0.20, -78.50, 10}, {"La Paz", -16.50, -68.13, 8}, {"Santiago", -33.45, -70.66, 16},
			{"Belo Horizonte", -19.92, -43.94, 12}, {"Brasilia", -15.79, -47.88, 14}, {"Salvador", -12.97, -38.50, 11},
			{"Fortaleza", -3.73, -38.53, 10}, {"Recife", -8.05, -34.90, 10}, {"Porto Alegre", -30.03, -51.22, 10},
	};

	private HornCulture() {
	}

	static float factor(double lat, double lon, String country) {
		return countryFactor(country) * cityFactor(lat, lon);
	}

	static float countryFactor(String country) {
		if (HONKING.contains(country)) return 2.5f;
		if (FAIRLY_HONKING.contains(country)) return 1.5f;
		if (QUIET.contains(country)) return 0.3f;
		if (FAIRLY_QUIET.contains(country)) return 0.6f;
		return 1f;
	}

	static float cityFactor(double lat, double lon) {
		if (inAny(BUSY, lat, lon)) return BUSY_CITY;
		if (inAny(MEDIUM, lat, lon)) return MEDIUM_CITY;
		return SMALL_TOWN;
	}

	private static boolean inAny(Object[][] cities, double lat, double lon) {
		double cosLat = Math.cos(Math.toRadians(lat));
		for (Object[] c : cities) {
			double dy = ((Number) c[1]).doubleValue() - lat;
			double dx = (((Number) c[2]).doubleValue() - lon) * cosLat;
			double km = ((Number) c[3]).doubleValue();
			if (Math.hypot(dx, dy) * 111.2 <= km) return true;
		}
		return false;
	}
}
