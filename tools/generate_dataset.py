"""Generate Sweep's realistic synthetic dataset (deterministic, seed=42).

Outputs (committed to the repo, loaded by the Java backend at startup):
  data/items.csv      id,name,category,base_price_inr
  data/stores.csv     id,name,type,area,x_km,y_km,open,close
  data/inventory.csv  store_id,item_id,price_inr,stock

Assumptions (documented in docs/REPORT.md):
  * Coordinates are on a flat km grid of Powai, Mumbai (~8 km x 6 km); landmark
    positions are approximate. Travel distance = 1.3 x Euclidean (a road-detour
    factor). Scaling a metric keeps it a metric, so the triangle inequality the
    TSP approximation relies on holds.
  * Prices are base MRP-style prices x a store-type multiplier x U(0.88, 1.15).
  * Stock is a small integer; ~6% of listings are deliberately low (1-2 units)
    so quantity feasibility (max-flow) actually matters.
Run:  python3 tools/generate_dataset.py
"""
import csv, random, pathlib

random.seed(42)
OUT = pathlib.Path(__file__).resolve().parent.parent / "data"
OUT.mkdir(exist_ok=True)

CATALOG = {
    "grocery": [("Basmati Rice 1kg", 145), ("Toor Dal 1kg", 160), ("Atta 5kg", 280), ("Sugar 1kg", 48),
                ("Salt 1kg", 25), ("Sunflower Oil 1L", 155), ("Mustard Oil 1L", 180), ("Ghee 500ml", 330),
                ("Moong Dal 500g", 85), ("Chana Dal 500g", 70), ("Poha 500g", 45), ("Rava 500g", 40),
                ("Besan 500g", 60), ("Turmeric Powder 100g", 35), ("Chilli Powder 100g", 40),
                ("Garam Masala 50g", 55), ("Jeera 100g", 60), ("Tea Leaves 250g", 140), ("Instant Coffee 50g", 165),
                ("Oats 1kg", 199), ("Cornflakes 500g", 185), ("Peanut Butter 340g", 225), ("Honey 250g", 130),
                ("Tomato Ketchup 500g", 115), ("Maggi Noodles 4pk", 56), ("Pasta 500g", 95), ("Soy Sauce 200ml", 75),
                ("Jam 500g", 150), ("Rajma 500g", 90), ("Kabuli Chana 500g", 85)],
    "dairy": [("Milk 1L", 66), ("Curd 400g", 45), ("Paneer 200g", 90), ("Butter 100g", 58), ("Cheese Slices 10pk", 140),
              ("Eggs 12pk", 96), ("Fresh Cream 200ml", 65), ("Buttermilk 500ml", 25), ("Greek Yogurt 100g", 50)],
    "produce": [("Onions 1kg", 40), ("Potatoes 1kg", 35), ("Tomatoes 1kg", 45), ("Bananas 12pc", 60), ("Apples 1kg", 180),
                ("Spinach Bunch", 25), ("Coriander Bunch", 15), ("Green Chillies 100g", 12), ("Ginger 100g", 20),
                ("Garlic 100g", 30), ("Lemons 6pc", 30), ("Cucumber 500g", 25), ("Carrots 500g", 30),
                ("Capsicum 500g", 50), ("Mangoes 1kg", 220), ("Oranges 1kg", 120), ("Papaya 1pc", 60)],
    "bakery": [("White Bread", 40), ("Brown Bread", 50), ("Pav 6pc", 30), ("Croissant", 60), ("Chocolate Cake 500g", 450),
               ("Rusk 300g", 55), ("Cookies 200g", 80), ("Burger Buns 4pc", 45), ("Garlic Bread", 90)],
    "snacks": [("Potato Chips 90g", 30), ("Namkeen Mix 400g", 110), ("Dark Chocolate 100g", 120), ("Biscuits Marie 250g", 35),
               ("Cola 750ml", 45), ("Orange Juice 1L", 120), ("Mineral Water 1L", 20), ("Energy Drink 250ml", 125),
               ("Popcorn Kernels 200g", 70), ("Roasted Almonds 200g", 260), ("Cashews 200g", 240), ("Ice Cream 700ml", 220)],
    "household": [("Dishwash Liquid 500ml", 110), ("Detergent Powder 1kg", 135), ("Floor Cleaner 1L", 175),
                  ("Toilet Cleaner 500ml", 95), ("Garbage Bags 30pc", 99), ("Aluminium Foil 9m", 115),
                  ("Tissue Roll 4pk", 140), ("Scrub Pad 3pk", 45), ("Mosquito Repellent Refill", 85),
                  ("Matchbox 10pk", 20), ("Agarbatti 100g", 60), ("Zip Lock Bags 20pc", 90), ("Kitchen Towel 2pk", 110)],
    "personal": [("Toothpaste 150g", 105), ("Toothbrush 2pk", 80), ("Shampoo 340ml", 230), ("Soap 4pk", 165),
                 ("Face Wash 100ml", 175), ("Deodorant 150ml", 210), ("Hand Wash 200ml", 99), ("Sanitary Pads 15pc", 180),
                 ("Shaving Cream 70g", 95), ("Razor 3pk", 140), ("Coconut Oil 200ml", 90), ("Sunscreen 50ml", 349),
                 ("Body Lotion 200ml", 245), ("Lip Balm", 120), ("Hair Oil 200ml", 150)],
    "pharmacy": [("Paracetamol 500mg Strip", 30), ("ORS Sachet 4pk", 80), ("Antiseptic Liquid 120ml", 95),
                 ("Bandage Roll", 40), ("Band-Aid 20pc", 60), ("Cough Syrup 100ml", 115), ("Antacid Tablets", 45),
                 ("Vitamin C Tablets", 120), ("Digital Thermometer", 199), ("Hand Sanitizer 100ml", 60),
                 ("Face Masks 10pc", 99), ("Pain Relief Spray", 185), ("Cotton Roll 100g", 55), ("Eye Drops 10ml", 90),
                 ("Glucose Powder 500g", 140), ("Antihistamine Strip", 35), ("Multivitamin 30tab", 299)],
    "stationery": [("A4 Notebook 200pg", 90), ("Ball Pen 5pk", 50), ("Gel Pen Blue", 20), ("Pencil 10pk", 60),
                   ("Eraser 3pk", 15), ("Sharpener", 10), ("Highlighter 4pk", 120), ("Sticky Notes", 75),
                   ("A4 Paper 500 sheets", 330), ("Stapler", 120), ("Staple Pins", 25), ("Glue Stick", 35),
                   ("Scissors", 70), ("File Folder", 30), ("Scale 30cm", 15), ("Whiteboard Marker", 40),
                   ("Calculator", 450), ("Geometry Box", 140), ("Cello Tape", 30)],
    "hardware": [("LED Bulb 9W", 120), ("Extension Board 4-socket", 399), ("AA Batteries 4pk", 120),
                 ("AAA Batteries 4pk", 120), ("Screwdriver Set", 249), ("Hammer", 299), ("Measuring Tape 5m", 150),
                 ("Insulation Tape", 30), ("Super Glue", 25), ("Wall Hooks 6pc", 99), ("Padlock", 220),
                 ("Nails Assorted 200g", 60), ("Plier", 199), ("Torch LED", 249), ("Door Stopper", 90)],
    "electronics": [("USB-C Cable 1m", 299), ("Lightning Cable 1m", 399), ("Phone Charger 20W", 799),
                    ("Wired Earphones", 349), ("Power Bank 10000mAh", 1299), ("Pendrive 32GB", 449),
                    ("Micro SD 64GB", 599), ("Mouse Wireless", 649), ("HDMI Cable 1.5m", 349),
                    ("Screen Guard", 199), ("Phone Cover", 299), ("Laptop Sleeve 14in", 799)],
    "pet": [("Dog Food 1kg", 420), ("Cat Food 1kg", 450), ("Cat Litter 5kg", 499), ("Dog Treats 100g", 180),
            ("Pet Shampoo 200ml", 299), ("Chew Toy", 199), ("Fish Food 50g", 120), ("Pet Bowl", 149)],
}

# store type -> (categories stocked, fraction of each category carried, price multiplier, count)
TYPES = {
    "Supermarket": (["grocery", "dairy", "produce", "bakery", "snacks", "household", "personal"], 0.80, 0.96, 4),
    "Kirana": (["grocery", "dairy", "snacks", "household"], 0.45, 1.00, 9),
    "Pharmacy": (["pharmacy", "personal"], 0.80, 1.02, 6),
    "Bakery": (["bakery", "dairy"], 0.70, 1.05, 3),
    "Fruit & Veg": (["produce"], 0.85, 0.92, 3),
    "Stationery": (["stationery"], 0.85, 1.00, 3),
    "Hardware": (["hardware", "household"], 0.65, 1.03, 3),
    "Electronics": (["electronics", "hardware"], 0.60, 1.05, 3),
    "Pet Store": (["pet"], 0.90, 1.04, 2),
}

AREAS = [("Hiranandani Gardens", 4.6, 3.4), ("IIT Bombay Main Gate", 2.4, 2.0), ("Powai Lake Promenade", 3.2, 3.9),
         ("Chandivali", 6.4, 2.2), ("Saki Vihar Road", 5.9, 4.4), ("Raheja Vihar", 6.9, 3.2),
         ("Panchkutir", 1.6, 4.7), ("Tirandaz Village", 3.9, 2.6), ("Galleria Square", 5.0, 3.1),
         ("Jogeshwari-Vikhroli Link Rd", 2.7, 5.2), ("Ramabai Nagar", 7.4, 1.4), ("Gandhinagar", 1.2, 1.6),
         ("Hill Side Avenue", 5.6, 2.6), ("Supreme Business Park", 5.2, 3.9), ("Kanjur Marg West", 1.0, 3.2),
         ("Morarji Nagar", 4.2, 5.4)]

NAME_BITS = {
    "Supermarket": ["FreshCart Hypermart", "Lakeside SuperBazaar", "DailyBasket Mega", "Greenfield Supermarket"],
    "Kirana": ["Shree Ganesh Kirana", "Laxmi General Store", "Om Sai Provisions", "Balaji Kirana", "Krishna Stores",
               "Mahalaxmi Traders", "Jai Ambe Kirana", "New Bombay Provisions", "Vinayak General"],
    "Pharmacy": ["CarePlus Chemist", "Lifeline Pharmacy", "Wellness Medicos", "Apex Chemists", "Sanjeevani Medical",
                 "HealthFirst Pharmacy"],
    "Bakery": ["Crumbs & Co.", "Irani Bake House", "Golden Oven"],
    "Fruit & Veg": ["Hariyali Fruits", "FarmFresh Sabzi", "Lakeview Fruit Mart"],
    "Stationery": ["Campus Stationers", "Inkwell Paper Mart", "Scholar's Corner"],
    "Hardware": ["Bharat Hardware", "FixIt Tools", "Mehta Electricals & Hardware"],
    "Electronics": ["Gadget Point", "Volt Mobile Hub", "TechKart Accessories"],
    "Pet Store": ["Paws & Whiskers", "Happy Tails Pet Shop"],
}

items, iid = [], 0
for cat, lst in CATALOG.items():
    for name, price in lst:
        iid += 1
        items.append((iid, name, cat, price))
by_cat = {}
for it in items:
    by_cat.setdefault(it[2], []).append(it)

stores, inventory, sid = [], [], 0
for stype, (cats, frac, mult, count) in TYPES.items():
    for k in range(count):
        sid += 1
        area, ax, ay = random.choice(AREAS)
        x = round(min(7.9, max(0.1, ax + random.uniform(-0.45, 0.45))), 2)
        y = round(min(5.9, max(0.1, ay + random.uniform(-0.45, 0.45))), 2)
        open_h = random.choice([7, 8, 9, 10]) if stype != "Pharmacy" else random.choice([0, 8])
        close_h = random.choice([21, 22, 23]) if open_h else 24
        stores.append((sid, NAME_BITS[stype][k], stype, area, x, y, open_h, close_h))
        for cat in cats:
            f = frac if cat == cats[0] else frac * 0.6
            for it in by_cat[cat]:
                if random.random() < f:
                    price = round(it[3] * mult * random.uniform(0.88, 1.15))
                    stock = random.choice([1, 2]) if random.random() < 0.06 else random.randint(3, 40)
                    inventory.append((sid, it[0], price, stock))

with open(OUT / "items.csv", "w", newline="") as f:
    w = csv.writer(f); w.writerow(["id", "name", "category", "base_price_inr"]); w.writerows(items)
with open(OUT / "stores.csv", "w", newline="") as f:
    w = csv.writer(f); w.writerow(["id", "name", "type", "area", "x_km", "y_km", "open", "close"]); w.writerows(stores)
with open(OUT / "inventory.csv", "w", newline="") as f:
    w = csv.writer(f); w.writerow(["store_id", "item_id", "price_inr", "stock"]); w.writerows(inventory)
print(f"{len(items)} items, {len(stores)} stores, {len(inventory)} inventory rows -> {OUT}")
